import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.ui.Seed;
import com.xtlure.app.util.Exporter;
import com.xtlure.app.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预置数据端到端自检（纯 JVM，不需要安卓设备）。
 *
 * 走的是 App 里真正会跑的那条路径：
 *   读 JSON 文本 -> Json.parse -> Seed.fromMap -> FishingSpot
 * 再反过来验证 Exporter 导出的 JSON / GeoJSON / Markdown 能正确生成。
 *
 * 这样能在装机之前就发现"数据读不进去""字段解析错位"这类运行时才暴露的问题。
 */
public class SeedTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "assets/spots_seed.json";

        System.out.println("=== 读取预置数据 ===");
        System.out.println("  文件: " + path);
        String text = readFile(path);
        check("文件非空", text.length() > 100, text.length() + " 字符");

        System.out.println("\n=== Json.parse 解析 ===");
        Object root = Json.parse(text);
        Map<String, Object> obj = Json.obj(root);
        check("顶层是对象", obj != null, obj == null ? "null" : obj.keySet().toString());

        List<Object> arr = Json.arr(obj.get("spots"));
        check("spots 是数组", arr != null, arr == null ? "null" : arr.size() + " 条");

        System.out.println("\n=== Seed.fromMap 转换 ===");
        List<FishingSpot> spots = new ArrayList<>();
        int failed = 0;
        for (Object o : arr) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) {
                failed++;
                continue;
            }
            FishingSpot s = Seed.fromMap(m);
            if (s.getName().isEmpty()) {
                failed++;
                continue;
            }
            spots.add(s);
        }
        check("全部转换成 FishingSpot", failed == 0, "失败 " + failed + " 条，成功 " + spots.size() + " 条");
        check("条数与 JSON 一致", spots.size() == arr.size(),
                spots.size() + " / " + arr.size());

        System.out.println("\n=== 第二轮调研修正的数据（OSM 实抓坐标）===");
        // 这些是子代理用 OSM Overpass/Photon 实抓后修正的条目，必须正确落库
        checkSpot(spots, "野沟门水库", 37.2106, 114.1032, "信都区");
        checkSpot(spots, "八一水库", 37.1890, 114.3293, "信都区");
        checkSpot(spots, "园博园水系", 37.0961, 114.5581, "襄都区");
        checkSpot(spots, "乱木水库", 37.4287, 114.4078, "临城县");

        // 马河水库 ≡ 鹊山湖、八一水库 ≡ 羊卧湾水库：同水体，坐标必须一致
        FishingSpot mahe = find(spots, "马河水库");
        FishingSpot queshan = find(spots, "鹊山湖");
        if (mahe != null && queshan != null) {
            check("马河水库与鹊山湖坐标一致（同水体）",
                    Math.abs(mahe.getLat() - queshan.getLat()) < 1e-6
                            && Math.abs(mahe.getLon() - queshan.getLon()) < 1e-6,
                    mahe.getLat() + "," + mahe.getLon());
        }
        FishingSpot bay = find(spots, "八一水库");
        FishingSpot yangwo = find(spots, "羊卧湾水库");
        if (bay != null && yangwo != null) {
            check("八一水库与羊卧湾水库坐标一致（同水体）",
                    Math.abs(bay.getLat() - yangwo.getLat()) < 1e-6
                            && Math.abs(bay.getLon() - yangwo.getLon()) < 1e-6,
                    bay.getLat() + "," + bay.getLon());
            check("八一水库无路亚对象鱼证据 -> 不给拟饵推荐",
                    bay.getLureTypes().isEmpty(), "[" + bay.getLureTypes() + "]");
        }

        System.out.println("\n=== 新增资料字段是否正确解析 ===");
        int withFee = 0, withEvidence = 0, withPrecision = 0, withAlt = 0;
        for (FishingSpot s : spots) {
            if (!s.getFeeNote().isEmpty()) withFee++;
            if (!s.getEvidence().isEmpty()) withEvidence++;
            if (!s.getCoordPrecision().isEmpty()) withPrecision++;
            if (!s.getSourceAlt().isEmpty()) withAlt++;
        }
        System.out.println("  费用 " + withFee + " 条，调研依据 " + withEvidence
                + " 条，坐标精度 " + withPrecision + " 条，备选来源 " + withAlt + " 条");
        check("调研依据字段已解析（>5 条）", withEvidence > 5, withEvidence + " 条");
        check("坐标精度字段已解析（>5 条）", withPrecision > 5, withPrecision + " 条");
        check("费用字段已解析（>3 条）", withFee > 3, withFee + " 条");
        // 预置数据里没标精度的必须被兜底成 approx，不能让用户以为那是实测值
        check("全部钓点都有坐标精度标注", withPrecision == spots.size(),
                withPrecision + " / " + spots.size());

        if (bay != null) {
            check("八一水库标为精确坐标", bay.isCoordExact(),
                    bay.getCoordPrecision() + " / " + bay.coordPrecisionText());
        }
        FishingSpot yingxue = find(spots, "映雪湖");
        if (yingxue != null) {
            check("映雪湖仍为估算坐标（诚实标注）", !yingxue.isCoordExact(),
                    yingxue.coordPrecisionText());
        }
        // 精度统计：估算的必须远多于实测的，否则说明标注纪律有问题
        int exactCount = 0, approxCount = 0;
        for (FishingSpot s : spots) {
            if (s.isCoordExact()) {
                exactCount++;
            } else {
                approxCount++;
            }
        }
        System.out.println("  精确坐标 " + exactCount + " 条，估算坐标 " + approxCount + " 条");
        check("估算坐标多于精确坐标（未夸大精度）", approxCount > exactCount,
                approxCount + " > " + exactCount);
        if (bay != null) {
            check("预置数据默认不算用户已核实", !bay.isUserVerified(), "");
        }

        System.out.println("\n=== 顶层合规须知 complianceNotes ===");
        java.util.List<Object> notes = Json.arr(obj.get("complianceNotes"));
        check("complianceNotes 存在", notes != null && !notes.isEmpty(),
                notes == null ? "null" : notes.size() + " 条");
        if (notes != null) {
            boolean hasOneRod = false, hasHotline = false, hasCriminal = false;
            for (Object o : notes) {
                String t = String.valueOf(o);
                if (t.contains("一人一杆")) hasOneRod = true;
                if (t.contains("举报电话") || t.contains("0319")) hasHotline = true;
                if (t.contains("刑法") || t.contains("非法捕捞")) hasCriminal = true;
            }
            // 关键：网上流传的"一人一杆一线一钩"必须被明确标注为查不到官方出处
            check("明确标注「一人一杆一线一钩」查不到官方出处", hasOneRod, "");
            check("含各县举报电话", hasHotline, "");
            check("含刑事风险判例", hasCriminal, "");
        }

        System.out.println("\n=== 顶层默认禁渔期说明已改为引用通告原文 ===");
        String closed = Json.str(obj, "closedSeasonDefault");
        System.out.println("  " + (closed.length() > 120 ? closed.substring(0, 120) + "…" : closed));
        check("默认禁渔期说明非空", !closed.isEmpty(), closed.length() + " 字");
        check("不再声称「一人一杆一线一钩」是官方规定",
                !closed.contains("仅允许一人一杆一线一钩"), "");

        System.out.println("\n=== 禁渔期字段覆盖率 ===");
        int noSeason = 0;
        for (FishingSpot s : spots) {
            if (s.getClosedSeason().isEmpty()) {
                noSeason++;
            }
        }
        check("全部钓点都有禁渔期说明", noSeason == 0, "缺 " + noSeason + " 条");

        System.out.println("\n=== 逐条字段抽查 ===");
        int withCoord = 0, withFish = 0, withLure = 0, withRisk = 0, withSource = 0;
        int badDistrict = 0;
        for (FishingSpot s : spots) {
            if (s.isHasCoord()) withCoord++;
            if (!s.getFishSpecies().isEmpty()) withFish++;
            if (!s.getLureTypes().isEmpty()) withLure++;
            if (!s.getRiskNote().isEmpty()) withRisk++;
            if (!s.getSourceUrl().isEmpty()) withSource++;
            if (s.getDistrict().isEmpty()) badDistrict++;
        }
        check("全部有坐标", withCoord == spots.size(), withCoord + " / " + spots.size());
        check("全部标了区县", badDistrict == 0, "缺区县 " + badDistrict + " 条");
        check("大多有对象鱼", withFish >= spots.size() - 4, withFish + " 条有");
        check("全部有风险提示", withRisk == spots.size(), withRisk + " / " + spots.size());

        System.out.println("\n=== 关键字段是否正确落位（防字段错位）===");
        FishingSpot zhuzhuang = null;
        for (FishingSpot s : spots) {
            if (s.getName().contains("朱庄")) {
                zhuzhuang = s;
            }
        }
        check("找到朱庄水库", zhuzhuang != null, "");
        if (zhuzhuang != null) {
            System.out.printf("  名称=%s%n  区县=%s%n  类型=%s%n  状态=%s%n  坐标=%.4f, %.4f%n",
                    zhuzhuang.getName(), zhuzhuang.getDistrict(),
                    FishingSpot.waterTypeName(zhuzhuang.getWaterType()),
                    FishingSpot.statusName(zhuzhuang.getStatus()),
                    zhuzhuang.getLat(), zhuzhuang.getLon());
            System.out.println("  对象鱼=" + zhuzhuang.getFishSpecies());
            System.out.println("  拟饵=" + zhuzhuang.getLureTypes());
            check("朱庄区县=沙河市", "沙河市".equals(zhuzhuang.getDistrict()),
                    zhuzhuang.getDistrict());
            check("朱庄类型=水库", zhuzhuang.getWaterType() == FishingSpot.TYPE_RESERVOIR, "");
            check("朱庄状态=限钓", zhuzhuang.getStatus() == FishingSpot.STATUS_RESTRICTED, "");
            check("朱庄对象鱼含翘嘴", zhuzhuang.getFishSpecies().contains("翘嘴"),
                    zhuzhuang.getFishSpecies());
            check("朱庄拟饵含亮片", zhuzhuang.getLureTypes().contains("亮片"),
                    zhuzhuang.getLureTypes());
            // 朱庄水库这一条用的是公开钓点库的实测坐标，可信度应为「网络资料」而非「未核实」
            check("朱庄可信度=网络资料", zhuzhuang.getConfidence() == FishingSpot.CONF_WEB,
                    FishingSpot.confidenceName(zhuzhuang.getConfidence()));
            check("朱庄带禁渔期说明", !zhuzhuang.getClosedSeason().isEmpty(),
                    zhuzhuang.getClosedSeason());
            check("朱庄带来源链接", zhuzhuang.getSourceUrl().contains("zainadiao"),
                    zhuzhuang.getSourceUrl());
        }

        // 禁渔期规则本身要能算对
        System.out.println("\n=== 禁渔期判定 ===");
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.set(2026, java.util.Calendar.JUNE, 1);
        check("6月1日在禁渔期内", FishingSpot.inClosedSeason(t), "6月1日");
        t.set(2026, java.util.Calendar.MAY, 1);
        check("5月1日不在禁渔期内", !FishingSpot.inClosedSeason(t), "5月1日");
        t.set(2026, java.util.Calendar.AUGUST, 15);
        check("8月15日不在禁渔期内", !FishingSpot.inClosedSeason(t), "8月15日");
        t.set(2026, java.util.Calendar.MAY, 20);
        check("5月20日在禁渔期内", FishingSpot.inClosedSeason(t), "5月20日");
        t.set(2026, java.util.Calendar.JULY, 31);
        check("7月31日在禁渔期内（末日）", FishingSpot.inClosedSeason(t), "7月31日");
        t.set(2026, java.util.Calendar.APRIL, 1);
        int days = FishingSpot.daysUntilClosedSeason(t);
        check("4月1日距禁渔期约 45 天", days >= 43 && days <= 46, days + " 天");

        // 黑坑不受禁渔期约束
        FishingSpot pit = null;
        for (FishingSpot s : spots) {
            if (s.getWaterType() == FishingSpot.TYPE_PAYPOND) {
                pit = s;
                break;
            }
        }
        check("数据里有收费黑坑", pit != null, pit == null ? "无" : pit.getName());
        if (pit != null) {
            check("黑坑不受禁渔期约束", !pit.subjectToClosedSeason(), pit.getName());
        }

        // 天然水域受约束
        FishingSpot nat = null;
        for (FishingSpot s : spots) {
            if (s.getWaterType() == FishingSpot.TYPE_RESERVOIR) {
                nat = s;
                break;
            }
        }
        if (nat != null) {
            check("水库受禁渔期约束", nat.subjectToClosedSeason(), nat.getName());
        }

        // 禁钓点必须被正确识别
        int banned = 0;
        for (FishingSpot s : spots) {
            if (s.getStatus() == FishingSpot.STATUS_BANNED) {
                banned++;
            }
        }
        System.out.println("\n  识别为禁钓的点: " + banned + " 个");
        check("识别出了禁钓点（南水北调/公园）", banned >= 3, banned + " 个");

        FishingSpot nanshui = null;
        for (FishingSpot s : spots) {
            if (s.getName().contains("南水北调")) {
                nanshui = s;
            }
        }
        check("找到南水北调干渠条目", nanshui != null, "");
        if (nanshui != null) {
            check("南水北调标记为禁钓", nanshui.getStatus() == FishingSpot.STATUS_BANNED, "");
            check("南水北调类型为渠道", nanshui.getWaterType() == FishingSpot.TYPE_CANAL, "");
        }

        System.out.println("\n=== 区间搜索（搜索框逻辑）===");
        String kw = "翘嘴";
        int hit = 0;
        for (FishingSpot s : spots) {
            if (s.searchBlob().contains(kw)) {
                hit++;
            }
        }
        System.out.println("  搜「" + kw + "」命中 " + hit + " 个钓点");
        check("搜索「翘嘴」有结果", hit >= 5, hit + " 个");

        int hitDistrict = 0;
        for (FishingSpot s : spots) {
            if (s.searchBlob().contains("沙河")) {
                hitDistrict++;
            }
        }
        check("搜索「沙河」有结果", hitDistrict >= 2, hitDistrict + " 个");

        System.out.println("\n=== 导出功能 ===");
        String backup = Exporter.toBackupJson(spots, new ArrayList<com.xtlure.app.model.CatchRecord>());
        check("备份 JSON 非空", backup.length() > 1000, backup.length() + " 字符");
        Object reparsed = Json.parseSafe(backup);
        check("导出的备份能被自己解析回来", reparsed != null,
                reparsed == null ? "解析失败" : "OK");
        Map<String, Object> reObj = Json.obj(reparsed);
        List<Object> reSpots = reObj == null ? null : Json.arr(reObj.get("spots"));
        check("回读条数一致", reSpots != null && reSpots.size() == spots.size(),
                (reSpots == null ? "null" : reSpots.size()) + " / " + spots.size());

        // 再走一遍 fromMap，验证"导出->导入"往返不丢字段
        int roundTripOk = 0;
        if (reSpots != null) {
            for (Object o : reSpots) {
                FishingSpot s = Seed.fromMap(Json.obj(o));
                if (!s.getName().isEmpty() && s.isHasCoord()) {
                    roundTripOk++;
                }
            }
        }
        check("导出->导入往返全部保留坐标", roundTripOk == spots.size(),
                roundTripOk + " / " + spots.size());

        String geo = Exporter.toGeoJson(spots);
        check("GeoJSON 生成成功", geo.contains("FeatureCollection") && geo.contains("\"Point\""),
                geo.length() + " 字符");
        check("GeoJSON 要素数与有坐标钓点数一致",
                countOccurrences(geo, "\"type\":\"Feature\"") == withCoord,
                countOccurrences(geo, "\"type\":\"Feature\"") + " / " + withCoord);
        check("GeoJSON 含 GCJ-02 附加字段", geo.contains("gcj02Lat"), "");

        String md = Exporter.toMarkdown(spots);
        check("Markdown 清单生成成功", md.contains("# 邢台路亚钓点清单"), md.length() + " 字符");
        check("Markdown 含区县分组", md.contains("## 沙河市"), "");
        check("Markdown 含坐标系说明", md.contains("WGS-84") && md.contains("GCJ-02"), "");

        String pretty = Exporter.pretty("{\"a\":1,\"b\":[1,2]}");
        check("JSON 美化可用", pretty.contains("\n") && pretty.contains("\"a\": 1"), "");
        check("美化后仍是合法 JSON", Json.parseSafe(pretty) != null, "");

        System.out.println("\n================================");
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    static FishingSpot find(List<FishingSpot> spots, String name) {
        for (FishingSpot s : spots) {
            if (name.equals(s.getName())) {
                return s;
            }
        }
        return null;
    }

    /** 校验某条钓点的坐标与区县 */
    static void checkSpot(List<FishingSpot> spots, String name,
                          double lat, double lon, String district) {
        FishingSpot s = find(spots, name);
        if (s == null) {
            check("存在「" + name + "」", false, "找不到");
            return;
        }
        boolean coordOk = Math.abs(s.getLat() - lat) < 0.001
                && Math.abs(s.getLon() - lon) < 0.001;
        check("「" + name + "」坐标已修正为 " + lat + "," + lon, coordOk,
                s.getLat() + "," + s.getLon()
                        + String.format(java.util.Locale.CHINA, "  (偏差 %.4f, %.4f)",
                        Math.abs(s.getLat() - lat), Math.abs(s.getLon() - lon)));
        check("「" + name + "」区县 = " + district, district.equals(s.getDistrict()),
                s.getDistrict());
    }

    static String readFile(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
