package com.xtlure.app.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.xtlure.app.data.Db;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.Json;
import com.xtlure.app.util.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预置钓点数据装载。
 *
 * 数据放在 assets/spots_seed.json，纯文本，改数据不用碰 Java 代码：
 * 编辑 JSON 后重新构建即可，App 首次启动时写入本地数据库。
 * 用版本号（SEED_VERSION）控制：只有版本变了才会重新灌入，不会覆盖你自己加的钓点。
 */
public final class Seed {

    private static final String TAG = "Seed";
    private static final String ASSET = "spots_seed.json";

    /**
     * 每次修改 seed 文件都把这个数字 +1，App 才会重新导入。
     *
     * 版本比较是"刷新已有钓点"的依据（见 importFromAsset）：
     * 库里某条记录的 seed_version 小于本值、且没被用户标记「实地确认」，就会用新资料覆盖它。
     *
     * v2：钓点扩到 73 条，加禁渔期字段
     * v3：2026 年调研修正乱木水库（内丘→临城）、八一水库（临城→信都区）的区县与坐标，
     *     野沟门水库、园博园水系坐标；合并马河水库≡鹊山湖、羊卧湾水库≡八一水库。
     * v4：v3 的坐标修正只在"库里还没有这条记录"时才会写入，
     *     已装过旧版、库里已存在同名记录的用户拿不到修正。
     *     本版起改为按 seed_version 比较后刷新（见 importFromAsset），
     *     并用 v4 强制走一遍刷新，让历史用户也能拿到 v3 的坐标修正。
     *     已在真机（vivo V2505A / Android 16）查库确认：
     *     73 条预置记录刷新为 seed_version=4，4 处修正坐标正确落库，
     *     用户自建钓点（seed_version=0）未被触碰。
     */
    private static final int SEED_VERSION = 4;

    /** 字段是否经过核实 */
    private static final String CONF_FIELD = "field";
    private static final String CONF_WEB = "web";

    private Seed() {
    }

    public static void loadIfNeeded(Context ctx) {
        int done = Prefs.getInt(ctx, Prefs.K_SEEDED, 0);
        if (done >= SEED_VERSION) {
            // 版本号说明已经灌过了，但库里可能一个预置钓点都没有 ——
            // 例如用户执行了"清空全部数据"。这种情况下自动重灌，
            // 否则界面会一直空着，用户只能卸载重装才能拿回内置数据。
            try {
                if (Db.get(ctx).countSpots() == 0) {
                    int n = importFromAsset(ctx);
                    Log.i(TAG, "检测到库为空，自动重灌预置钓点: " + n + " 条");
                }
            } catch (Exception e) {
                Log.e(TAG, "自动重灌失败", e);
            }
            return;
        }
        try {
            int n = importFromAsset(ctx);
            Prefs.setInt(ctx, Prefs.K_SEEDED, SEED_VERSION);
            Log.i(TAG, "预置钓点导入完成: " + n + " 条");
        } catch (Exception e) {
            // 导入失败不能影响 App 启动，下次启动会再试
            Log.e(TAG, "预置钓点导入失败", e);
        }
    }

    /** @return 成功写入的条数 */
    public static int importFromAsset(Context ctx) throws Exception {
        String text = readAsset(ctx, ASSET);
        if (text == null) {
            return 0;
        }
        Object root = Json.parse(text);
        Map<String, Object> obj = Json.obj(root);
        if (obj == null) {
            return 0;
        }
        List<Object> arr = Json.arr(obj.get("spots"));
        if (arr == null) {
            return 0;
        }

        Db db = Db.get(ctx);
        String defaultClosedSeason = Json.str(obj, "closedSeasonDefault");
        int added = 0;
        int refreshed = 0;
        int skippedUserVerified = 0;

        for (Object o : arr) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) {
                continue;
            }
            FishingSpot s = fromMap(m);
            if (s.getName().isEmpty()) {
                continue;
            }
            // 禁渔期说明：本条没写就用文件级的默认说明兜底
            if (s.getClosedSeason().isEmpty() && !defaultClosedSeason.isEmpty()) {
                s.setClosedSeason(defaultClosedSeason);
            }
            s.setSeedVersion(SEED_VERSION);

            FishingSpot existing = db.findSpotByName(s.getName());
            if (existing == null) {
                db.insertSpot(s);
                added++;
                continue;
            }

            // ---- 已存在：决定要不要用新版预置数据刷新 ----
            // 旧版本导进来的时候还没来得及写版本号，视为 0，应当刷新。
            int existingVersion = existing.getSeedVersion();

            if (existingVersion >= SEED_VERSION) {
                continue; // 同一版或更新，不重复刷
            }
            if (existing.isUserVerified()) {
                // 用户自己实地核实过的数据，永远不覆盖 —— 那比任何公开资料都准
                skippedUserVerified++;
                db.markSeedVersion(existing.getId(), SEED_VERSION);
                continue;
            }
            db.refreshFromSeed(existing, s);
            refreshed++;
        }

        Log.i(TAG, "预置数据 v" + SEED_VERSION + " 导入完成：新增 " + added
                + "，修正 " + refreshed + "，跳过用户已核实 " + skippedUserVerified);
        return added;
    }

    /** 把 JSON 对象转成钓点。本方法也用于"从 JSON 文件恢复备份"。 */
    public static FishingSpot fromMap(Map<String, Object> m) {
        FishingSpot s = new FishingSpot();
        s.setName(Json.str(m, "name").trim());

        double lat = Json.num(m, "lat", Double.NaN);
        double lon = Json.num(m, "lon", Double.NaN);
        if (!Double.isNaN(lat) && !Double.isNaN(lon) && (lat != 0 || lon != 0)) {
            s.setLat(lat);
            s.setLon(lon);
            s.setHasCoord(true);
        }

        s.setWaterType(waterTypeFrom(m));
        s.setStatus(statusFrom(m));
        s.setDistrict(Json.str(m, "district").trim());
        s.setAddress(Json.str(m, "address").trim());

        // 支持字符串数组或逗号分隔字符串两种写法
        s.setFishSpecies(join(Json.strList(m, "fish"), Json.str(m, "fishSpecies")));
        s.setLureTypes(join(Json.strList(m, "lure"), Json.str(m, "lureTypes")));
        s.setBestSeason(Json.str(m, "bestSeason").trim());
        s.setAccessNote(Json.str(m, "access").trim());
        s.setNote(Json.str(m, "note").trim());
        s.setRiskNote(Json.str(m, "risk").trim());
        s.setClosedSeason(Json.str(m, "closedSeason").trim());
        s.setSourceUrl(Json.str(m, "source").trim());
        s.setFeeNote(Json.str(m, "fee").trim());
        s.setEvidence(Json.str(m, "evidence").trim());

        // 坐标精度：没标的按「估算」处理。
        // 宁可低估自己 —— 把估算坐标说成实测，用户到了水边找不到点是实实在在的损失。
        String precision = Json.str(m, "coordPrecision").trim();
        if (precision.isEmpty()) {
            precision = (s.getConfidence() == FishingSpot.CONF_FIELD
                    || s.getConfidence() == FishingSpot.CONF_WEB) ? "exact" : "approx";
        }
        s.setCoordPrecision(precision);

        // sourceAlt 可能是数组，也可能是竖线分隔的字符串
        List<String> alts = Json.strList(m, "sourceAlt");
        if (!alts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < alts.size(); i++) {
                if (i > 0) {
                    sb.append('|');
                }
                sb.append(alts.get(i).trim());
            }
            s.setSourceAlt(sb.toString());
        } else {
            s.setSourceAlt(Json.str(m, "sourceAlt").trim());
        }

        String conf = Json.str(m, "confidence").trim().toLowerCase();
        if (CONF_FIELD.equals(conf)) {
            s.setConfidence(FishingSpot.CONF_FIELD);
        } else if (CONF_WEB.equals(conf)) {
            s.setConfidence(FishingSpot.CONF_WEB);
        } else {
            s.setConfidence(FishingSpot.CONF_UNVERIFIED);
        }

        s.setRating(Json.intVal(m, "rating", 0));
        s.setFavorite(Json.bool(m, "favorite", false));
        return s;
    }

    private static String join(List<String> arr, String fallback) {
        if (arr != null && !arr.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) {
                    sb.append('、');
                }
                sb.append(arr.get(i).trim());
            }
            return sb.toString();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static int waterTypeFrom(Map<String, Object> m) {
        String t = Json.str(m, "waterType").trim();
        if (t.isEmpty()) {
            return FishingSpot.TYPE_UNKNOWN;
        }
        if (t.contains("水库")) return FishingSpot.TYPE_RESERVOIR;
        if (t.contains("河") || t.contains("江")) return FishingSpot.TYPE_RIVER;
        if (t.contains("湖")) return FishingSpot.TYPE_LAKE;
        if (t.contains("黑坑") || t.contains("收费") || t.contains("钓场")) {
            return FishingSpot.TYPE_PAYPOND;
        }
        if (t.contains("塘") || t.contains("池")) return FishingSpot.TYPE_POND;
        if (t.contains("渠") || t.contains("干渠")) return FishingSpot.TYPE_CANAL;
        if (t.contains("沙坑") || t.contains("矿坑")) return FishingSpot.TYPE_PIT;
        return FishingSpot.TYPE_UNKNOWN;
    }

    private static int statusFrom(Map<String, Object> m) {
        String t = Json.str(m, "status").trim();
        if (t.isEmpty()) {
            return FishingSpot.STATUS_UNKNOWN;
        }
        if (t.contains("禁")) return FishingSpot.STATUS_BANNED;
        if (t.contains("收费") || t.contains("黑坑")) return FishingSpot.STATUS_FEE;
        if (t.contains("限") || t.contains("需")) return FishingSpot.STATUS_RESTRICTED;
        if (t.contains("可钓") || t.contains("开放") || t.contains("免费")) {
            return FishingSpot.STATUS_OPEN;
        }
        return FishingSpot.STATUS_UNKNOWN;
    }

    // ---------------------------------------------------------------- IO

    public static String readAsset(Context ctx, String name) {
        try {
            AssetManager am = ctx.getAssets();
            InputStream in = am.open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "读不到 assets/" + name + ": " + e.getMessage());
            return null;
        }
    }

    /** 预置数据里可能还带了示例记录 */
    public static List<Map<String, Object>> readSeedCatches(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        String text = readAsset(ctx, ASSET);
        if (text == null) {
            return out;
        }
        Map<String, Object> obj = Json.obj(Json.parseSafe(text));
        if (obj == null) {
            return out;
        }
        List<Object> arr = Json.arr(obj.get("catches"));
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            Map<String, Object> m = Json.obj(o);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }
}
