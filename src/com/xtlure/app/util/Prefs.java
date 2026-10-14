package com.xtlure.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量偏好设置。 */
public final class Prefs {

    private static final String FILE = "xtlure_prefs";

    public static final String K_DISTRICT_FILTER = "district_filter";
    public static final String K_MAP_PROVIDER = "map_provider";
    public static final String K_SORT_MODE = "sort_mode";
    public static final String K_SEEDED = "seeded_version";
    public static final String K_LAST_LAT = "last_lat";
    public static final String K_LAST_LON = "last_lon";
    public static final String K_SHOW_BANNED = "show_banned";

    /** 地图通道：auto / amap / baidu / tencent / web */
    public static final String MAP_AUTO = "auto";

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getString(Context c, String k, String def) {
        return get(c).getString(k, def);
    }

    public static void setString(Context c, String k, String v) {
        get(c).edit().putString(k, v).apply();
    }

    public static int getInt(Context c, String k, int def) {
        return get(c).getInt(k, def);
    }

    public static void setInt(Context c, String k, int v) {
        get(c).edit().putInt(k, v).apply();
    }

    public static boolean getBool(Context c, String k, boolean def) {
        return get(c).getBoolean(k, def);
    }

    public static void setBool(Context c, String k, boolean v) {
        get(c).edit().putBoolean(k, v).apply();
    }

    public static double getDouble(Context c, String k, double def) {
        return Double.longBitsToDouble(get(c).getLong(k, Double.doubleToLongBits(def)));
    }

    public static void setDouble(Context c, String k, double v) {
        get(c).edit().putLong(k, Double.doubleToLongBits(v)).apply();
    }
}
