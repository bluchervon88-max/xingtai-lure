package com.xtlure.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.AppCtx;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 SQLite 存储。全部数据落在手机上，不联网、不上传。
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "xtlure.db";

    /** v2：CatchRecord 增加气压、气温、水温；v3：FishingSpot 增加禁渔期；
     *  v4：FishingSpot 增加资料字段（费用/证据/坐标精度/备选来源）+ seed_version */
    private static final int VERSION = 4;

    public static final String T_SPOT = "spot";
    public static final String T_CATCH = "catch";

    private static Db instance;

    public static synchronized Db get(Context ctx) {
        if (instance == null) {
            Context c = ctx == null ? AppCtx.get() : ctx.getApplicationContext();
            if (c == null) {
                throw new IllegalStateException("Db.get 需要 Context，且 AppCtx 尚未初始化");
            }
            instance = new Db(c);
        }
        return instance;
    }

    private Db(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_SPOT + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "lat REAL, lon REAL, has_coord INTEGER DEFAULT 0,"
                + "water_type INTEGER DEFAULT 0,"
                + "status INTEGER DEFAULT 0,"
                + "district TEXT, address TEXT,"
                + "fish_species TEXT, lure_types TEXT, best_season TEXT,"
                + "access_note TEXT, note TEXT, risk_note TEXT, closed_season TEXT, source_url TEXT,"
                + "fee_note TEXT, evidence TEXT, source_alt TEXT, coord_precision TEXT,"
                + "seed_version INTEGER DEFAULT 0,"
                + "confidence INTEGER DEFAULT 0,"
                + "rating INTEGER DEFAULT 0,"
                + "favorite INTEGER DEFAULT 0,"
                + "created_at INTEGER, updated_at INTEGER"
                + ")");

        db.execSQL("CREATE TABLE " + T_CATCH + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "spot_id INTEGER DEFAULT -1,"
                + "spot_name TEXT,"
                + "time INTEGER,"
                + "target_fish TEXT, caught_fish TEXT,"
                + "count INTEGER DEFAULT 0,"
                + "size_note TEXT, lure_used TEXT, method TEXT,"
                + "lat REAL, lon REAL, has_coord INTEGER DEFAULT 0,"
                + "weather TEXT, wind TEXT, water_note TEXT, note TEXT,"
                + "pressure TEXT, air_temp TEXT, water_temp TEXT,"
                + "rating INTEGER DEFAULT 0,"
                + "created_at INTEGER"
                + ")");

        db.execSQL("CREATE INDEX idx_spot_name ON " + T_SPOT + "(name)");
        db.execSQL("CREATE INDEX idx_catch_spot ON " + T_CATCH + "(spot_id)");
        db.execSQL("CREATE INDEX idx_catch_time ON " + T_CATCH + "(time)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 逐级升级，绝不 DROP TABLE —— 用户数据比表结构重要。
        if (oldVersion < 2) {
            // v2: 增加气压、气温、水温
            try {
                db.execSQL("ALTER TABLE " + T_CATCH + " ADD COLUMN pressure TEXT");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE " + T_CATCH + " ADD COLUMN air_temp TEXT");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE " + T_CATCH + " ADD COLUMN water_temp TEXT");
            } catch (Exception ignored) {
            }
        }
        if (oldVersion < 3) {
            // v3: 钓点增加禁渔期说明
            try {
                db.execSQL("ALTER TABLE " + T_SPOT + " ADD COLUMN closed_season TEXT");
            } catch (Exception ignored) {
            }
        }
        if (oldVersion < 4) {
            // v4: 钓点增加调研资料字段与预置数据版本号
            for (String col : new String[]{"fee_note TEXT", "evidence TEXT",
                    "source_alt TEXT", "coord_precision TEXT",
                    "seed_version INTEGER DEFAULT 0"}) {
                try {
                    db.execSQL("ALTER TABLE " + T_SPOT + " ADD COLUMN " + col);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ================================================================ 钓点

    public long insertSpot(FishingSpot s) {
        long now = System.currentTimeMillis();
        if (s.getCreatedAt() == 0) {
            s.setCreatedAt(now);
        }
        s.setUpdatedAt(now);
        long id = getWritableDatabase().insert(T_SPOT, null, toValues(s));
        s.setId(id);
        return id;
    }

    public int updateSpot(FishingSpot s) {
        s.setUpdatedAt(System.currentTimeMillis());
        return getWritableDatabase().update(T_SPOT, toValues(s), "id=?",
                new String[]{String.valueOf(s.getId())});
    }

    public int deleteSpot(long id) {
        return getWritableDatabase().delete(T_SPOT, "id=?", new String[]{String.valueOf(id)});
    }

    public FishingSpot getSpot(long id) {
        Cursor c = getReadableDatabase().query(T_SPOT, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return spotFromCursor(c);
            }
        } finally {
            c.close();
        }
        return null;
    }

    public List<FishingSpot> listSpots() {
        return querySpots(null, null);
    }

    /** 支持按关键词过滤的查询 */
    public List<FishingSpot> querySpots(String keyword, String orderBy) {
        List<FishingSpot> out = new ArrayList<>();
        String sel = null;
        String[] args = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = "%" + keyword.trim().toLowerCase() + "%";
            sel = "(LOWER(name) LIKE ? OR LOWER(district) LIKE ? OR LOWER(address) LIKE ?"
                    + " OR LOWER(fish_species) LIKE ? OR LOWER(lure_types) LIKE ?"
                    + " OR LOWER(note) LIKE ?)";
            args = new String[]{k, k, k, k, k, k};
        }
        if (orderBy == null) {
            orderBy = "favorite DESC, updated_at DESC";
        }
        Cursor c = getReadableDatabase().query(T_SPOT, null, sel, args, null, null, orderBy);
        try {
            while (c.moveToNext()) {
                out.add(spotFromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int countSpots() {
        return count(T_SPOT, null, null);
    }

    /**
     * 用户**自己添加**的钓点数（不含 App 预置的）。
     *
     * 预置钓点带 seed_version >= 1，用户新建的默认是 0。
     * 这个区分很关键：预置数据是 App 自带的，不该算成用户的成果 ——
     * 否则新装用户一进"我的"页就白拿好几枚成就，成就也就失去意义了。
     */
    public int countUserSpots() {
        return rawCount("SELECT COUNT(*) FROM " + T_SPOT + " WHERE seed_version=0");
    }

    /** 用户自己添加、且带坐标的钓点数 */
    public int countUserSpotsWithCoord() {
        return rawCount("SELECT COUNT(*) FROM " + T_SPOT
                + " WHERE seed_version=0 AND has_coord=1");
    }

    /** 用户去过的不同钓点数（有作钓记录的钓点） */
    public int countVisitedSpots() {
        return rawCount("SELECT COUNT(DISTINCT spot_id) FROM " + T_CATCH
                + " WHERE spot_id > 0");
    }

    /** 用过的不同拟饵数 */
    public int countDistinctLures() {
        return rawCount("SELECT COUNT(DISTINCT lure_used) FROM " + T_CATCH
                + " WHERE lure_used IS NOT NULL AND lure_used <> ''");
    }

    /** 有渔获（尾数 > 0）的记录数 */
    public int countSuccessfulCatches() {
        return rawCount("SELECT COUNT(*) FROM " + T_CATCH + " WHERE count > 0");
    }

    /** 出现过渔获的不同鱼种数 */
    public int countDistinctSpecies() {
        return rawCount("SELECT COUNT(DISTINCT caught_fish) FROM " + T_CATCH
                + " WHERE caught_fish IS NOT NULL AND caught_fish <> ''");
    }

    /** 有作钓记录的不同日期数（按本地日切分） */
    public int countFishingDays() {
        return rawCount("SELECT COUNT(DISTINCT date(time/1000, 'unixepoch', 'localtime'))"
                + " FROM " + T_CATCH);
    }

    /** 跑一条只取一个整数的 SQL */
    private int rawCount(String sql) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(sql, null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Exception ignored) {
            // 表或列还没建好时静默返回 0，不要因为统计失败影响界面
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    public boolean spotNameExists(String name, long excludeId) {
        Cursor c = getReadableDatabase().query(T_SPOT, new String[]{"id"}, "name=? AND id<>?",
                new String[]{name, String.valueOf(excludeId)}, null, null, null);
        try {
            return c.getCount() > 0;
        } finally {
            c.close();
        }
    }

    private ContentValues toValues(FishingSpot s) {
        ContentValues v = new ContentValues();
        v.put("name", s.getName());
        v.put("lat", s.getLat());
        v.put("lon", s.getLon());
        v.put("has_coord", s.isHasCoord() ? 1 : 0);
        v.put("water_type", s.getWaterType());
        v.put("status", s.getStatus());
        v.put("district", s.getDistrict());
        v.put("address", s.getAddress());
        v.put("fish_species", s.getFishSpecies());
        v.put("lure_types", s.getLureTypes());
        v.put("best_season", s.getBestSeason());
        v.put("access_note", s.getAccessNote());
        v.put("note", s.getNote());
        v.put("risk_note", s.getRiskNote());
        v.put("closed_season", s.getClosedSeason());
        v.put("source_url", s.getSourceUrl());
        v.put("fee_note", s.getFeeNote());
        v.put("evidence", s.getEvidence());
        v.put("source_alt", s.getSourceAlt());
        v.put("coord_precision", s.getCoordPrecision());
        v.put("seed_version", s.getSeedVersion());
        v.put("confidence", s.getConfidence());
        v.put("rating", s.getRating());
        v.put("favorite", s.isFavorite() ? 1 : 0);
        v.put("created_at", s.getCreatedAt());
        v.put("updated_at", s.getUpdatedAt());
        return v;
    }

    private FishingSpot spotFromCursor(Cursor c) {
        FishingSpot s = new FishingSpot();
        s.setId(c.getLong(c.getColumnIndexOrThrow("id")));
        s.setName(c.getString(c.getColumnIndexOrThrow("name")));
        s.setLat(c.getDouble(c.getColumnIndexOrThrow("lat")));
        s.setLon(c.getDouble(c.getColumnIndexOrThrow("lon")));
        s.setHasCoord(c.getInt(c.getColumnIndexOrThrow("has_coord")) == 1);
        s.setWaterType(c.getInt(c.getColumnIndexOrThrow("water_type")));
        s.setStatus(c.getInt(c.getColumnIndexOrThrow("status")));
        s.setDistrict(c.getString(c.getColumnIndexOrThrow("district")));
        s.setAddress(c.getString(c.getColumnIndexOrThrow("address")));
        s.setFishSpecies(c.getString(c.getColumnIndexOrThrow("fish_species")));
        s.setLureTypes(c.getString(c.getColumnIndexOrThrow("lure_types")));
        s.setBestSeason(c.getString(c.getColumnIndexOrThrow("best_season")));
        s.setAccessNote(c.getString(c.getColumnIndexOrThrow("access_note")));
        s.setNote(c.getString(c.getColumnIndexOrThrow("note")));
        s.setRiskNote(c.getString(c.getColumnIndexOrThrow("risk_note")));
        s.setClosedSeason(getStr(c, "closed_season"));
        s.setSourceUrl(c.getString(c.getColumnIndexOrThrow("source_url")));
        s.setFeeNote(getStr(c, "fee_note"));
        s.setEvidence(getStr(c, "evidence"));
        s.setSourceAlt(getStr(c, "source_alt"));
        s.setCoordPrecision(getStr(c, "coord_precision"));
        s.setSeedVersion(getInt(c, "seed_version"));
        s.setConfidence(c.getInt(c.getColumnIndexOrThrow("confidence")));
        s.setRating(c.getInt(c.getColumnIndexOrThrow("rating")));
        s.setFavorite(c.getInt(c.getColumnIndexOrThrow("favorite")) == 1);
        s.setCreatedAt(c.getLong(c.getColumnIndexOrThrow("created_at")));
        s.setUpdatedAt(c.getLong(c.getColumnIndexOrThrow("updated_at")));
        return s;
    }

    // ================================================================ 记录

    public long insertCatch(CatchRecord r) {
        long now = System.currentTimeMillis();
        if (r.getCreatedAt() == 0) {
            r.setCreatedAt(now);
        }
        if (r.getTime() == 0) {
            r.setTime(now);
        }
        long id = getWritableDatabase().insert(T_CATCH, null, toValues(r));
        r.setId(id);
        return id;
    }

    public int updateCatch(CatchRecord r) {
        return getWritableDatabase().update(T_CATCH, toValues(r), "id=?",
                new String[]{String.valueOf(r.getId())});
    }

    public int deleteCatch(long id) {
        return getWritableDatabase().delete(T_CATCH, "id=?", new String[]{String.valueOf(id)});
    }

    public List<CatchRecord> listCatches() {
        return queryCatches(null, -1);
    }

    public CatchRecord getCatch(long id) {
        Cursor c = getReadableDatabase().query(T_CATCH, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return catchFromCursor(c);
            }
        } finally {
            c.close();
        }
        return null;
    }

    /** 清空全部数据（危险操作，UI 需要二次确认） */
    public void wipeAll() {
        getWritableDatabase().delete(T_SPOT, null, null);
        getWritableDatabase().delete(T_CATCH, null, null);
    }

    public List<CatchRecord> queryCatches(String keyword, long spotId) {
        List<CatchRecord> out = new ArrayList<>();
        StringBuilder sel = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (spotId > 0) {
            sel.append("spot_id=?");
            args.add(String.valueOf(spotId));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            if (sel.length() > 0) {
                sel.append(" AND ");
            }
            String k = "%" + keyword.trim().toLowerCase() + "%";
            sel.append("(LOWER(spot_name) LIKE ? OR LOWER(caught_fish) LIKE ?"
                    + " OR LOWER(lure_used) LIKE ? OR LOWER(note) LIKE ?)");
            args.add(k);
            args.add(k);
            args.add(k);
            args.add(k);
        }
        Cursor c = getReadableDatabase().query(T_CATCH, null,
                sel.length() == 0 ? null : sel.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]),
                null, null, "time DESC");
        try {
            while (c.moveToNext()) {
                out.add(catchFromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int countCatches() {
        return count(T_CATCH, null, null);
    }

    public int countCatchesForSpot(long spotId) {
        return count(T_CATCH, "spot_id=?", new String[]{String.valueOf(spotId)});
    }

    /** 某钓点最近一次渔获时间，无记录返回 0 */
    public long lastCatchTime(long spotId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(time) FROM " + T_CATCH + " WHERE spot_id=?",
                new String[]{String.valueOf(spotId)});
        try {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } finally {
            c.close();
        }
        return 0;
    }

    /** 某钓点累计尾数 */
    public int totalFishForSpot(long spotId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(count) FROM " + T_CATCH + " WHERE spot_id=?",
                new String[]{String.valueOf(spotId)});
        try {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getInt(0);
            }
        } finally {
            c.close();
        }
        return 0;
    }

    private ContentValues toValues(CatchRecord r) {
        ContentValues v = new ContentValues();
        v.put("spot_id", r.getSpotId());
        v.put("spot_name", r.getSpotName());
        v.put("time", r.getTime());
        v.put("target_fish", r.getTargetFish());
        v.put("caught_fish", r.getCaughtFish());
        v.put("count", r.getCount());
        v.put("size_note", r.getSizeNote());
        v.put("lure_used", r.getLureUsed());
        v.put("method", r.getMethod());
        v.put("lat", r.getLat());
        v.put("lon", r.getLon());
        v.put("has_coord", r.isHasCoord() ? 1 : 0);
        v.put("weather", r.getWeather());
        v.put("wind", r.getWind());
        v.put("water_note", r.getWaterNote());
        v.put("note", r.getNote());
        v.put("pressure", r.getPressure());
        v.put("air_temp", r.getAirTemp());
        v.put("water_temp", r.getWaterTemp());
        v.put("rating", r.getRating());
        v.put("created_at", r.getCreatedAt());
        return v;
    }

    private CatchRecord catchFromCursor(Cursor c) {
        CatchRecord r = new CatchRecord();
        r.setId(c.getLong(c.getColumnIndexOrThrow("id")));
        r.setSpotId(c.getLong(c.getColumnIndexOrThrow("spot_id")));
        r.setSpotName(c.getString(c.getColumnIndexOrThrow("spot_name")));
        r.setTime(c.getLong(c.getColumnIndexOrThrow("time")));
        r.setTargetFish(c.getString(c.getColumnIndexOrThrow("target_fish")));
        r.setCaughtFish(c.getString(c.getColumnIndexOrThrow("caught_fish")));
        r.setCount(c.getInt(c.getColumnIndexOrThrow("count")));
        r.setSizeNote(c.getString(c.getColumnIndexOrThrow("size_note")));
        r.setLureUsed(c.getString(c.getColumnIndexOrThrow("lure_used")));
        r.setMethod(c.getString(c.getColumnIndexOrThrow("method")));
        r.setLat(c.getDouble(c.getColumnIndexOrThrow("lat")));
        r.setLon(c.getDouble(c.getColumnIndexOrThrow("lon")));
        r.setHasCoord(c.getInt(c.getColumnIndexOrThrow("has_coord")) == 1);
        r.setWeather(c.getString(c.getColumnIndexOrThrow("weather")));
        r.setWind(c.getString(c.getColumnIndexOrThrow("wind")));
        r.setWaterNote(c.getString(c.getColumnIndexOrThrow("water_note")));
        r.setNote(c.getString(c.getColumnIndexOrThrow("note")));
        r.setPressure(getStr(c, "pressure"));
        r.setAirTemp(getStr(c, "air_temp"));
        r.setWaterTemp(getStr(c, "water_temp"));
        r.setRating(c.getInt(c.getColumnIndexOrThrow("rating")));
        r.setCreatedAt(c.getLong(c.getColumnIndexOrThrow("created_at")));
        return r;
    }

    /** 读可能不存在的列（老版本升级上来的库，防御性处理） */
    private static String getStr(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) {
            return "";
        }
        return c.getString(i);
    }

    private static int getInt(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) {
            return 0;
        }
        return c.getInt(i);
    }

    // ================================================================ 预置数据更新

    /** 已存在的同名钓点（按名字精确匹配），不存在返回 null */
    public FishingSpot findSpotByName(String name) {
        Cursor c = getReadableDatabase().query(T_SPOT, null, "name=?",
                new String[]{name}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return spotFromCursor(c);
            }
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * 只更新预置数据版本号，不动其他字段。
     * 用于"用户已自己核实过"的钓点：记下已处理到哪一版，避免每次启动重复判断。
     */
    public int markSeedVersion(long spotId, int version) {
        ContentValues v = new ContentValues();
        v.put("seed_version", version);
        return getWritableDatabase().update(T_SPOT, v, "id=?",
                new String[]{String.valueOf(spotId)});
    }

    /**
     * 用新版预置数据刷新一条已有钓点。
     *
     * 只覆盖"资料性"字段，**保留用户自己的东西**（备注、评分、收藏、创建时间）。
     * 调用方必须自行判断"这条是否允许被覆盖"——见 Seed 里的置信度保护逻辑。
     */
    public int refreshFromSeed(FishingSpot existing, FishingSpot fresh) {
        ContentValues v = new ContentValues();
        v.put("lat", fresh.getLat());
        v.put("lon", fresh.getLon());
        v.put("has_coord", fresh.isHasCoord() ? 1 : 0);
        v.put("water_type", fresh.getWaterType());
        v.put("status", fresh.getStatus());
        v.put("district", fresh.getDistrict());
        v.put("address", fresh.getAddress());
        v.put("fish_species", fresh.getFishSpecies());
        v.put("lure_types", fresh.getLureTypes());
        v.put("best_season", fresh.getBestSeason());
        v.put("access_note", fresh.getAccessNote());
        v.put("risk_note", fresh.getRiskNote());
        v.put("closed_season", fresh.getClosedSeason());
        v.put("source_url", fresh.getSourceUrl());
        v.put("fee_note", fresh.getFeeNote());
        v.put("evidence", fresh.getEvidence());
        v.put("source_alt", fresh.getSourceAlt());
        v.put("coord_precision", fresh.getCoordPrecision());
        v.put("confidence", fresh.getConfidence());
        v.put("seed_version", fresh.getSeedVersion());
        // 注意：note / rating / favorite / created_at 不动，那是用户自己的内容
        v.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(T_SPOT, v, "id=?",
                new String[]{String.valueOf(existing.getId())});
    }

    // ================================================================ 通用

    private int count(String table, String sel, String[] args) {
        Cursor c = getReadableDatabase().query(table, new String[]{"COUNT(*)"}, sel, args,
                null, null, null);
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            c.close();
        }
        return 0;
    }
}
