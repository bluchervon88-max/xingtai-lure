package com.xtlure.app.core;

/**
 * WGS-84 <-> GCJ-02 <-> BD-09 coordinate conversion.
 *
 * 手机 GPS 拿到的是 WGS-84 原始坐标，国内地图（高德/腾讯）使用 GCJ-02（火星坐标），
 * 百度使用 BD-09。把点标到地图上之前必须转换，否则会有几百米偏移。
 *
 * 算法源自公开的坐标偏移标准实现，此处为独立 Java 移植，无第三方依赖。
 * 参考项目：https://github.com/ipcjs/coordtransform (MIT)
 */
public final class CoordinateTransform {

    private static final double PI = 3.1415926535897932384626;

    /** GCJ-02 长半轴 */
    private static final double A = 6378245.0;
    /** GCJ-02 偏心率平方 */
    private static final double EE = 0.00669342162296594323;

    /** 百度坐标额外偏移量 */
    private static final double X_PI = PI * 3000.0 / 180.0;

    private CoordinateTransform() {
    }

    /** 是否在中国境外（境外不做偏移） */
    public static boolean outOfChina(double lat, double lon) {
        if (lon < 72.004 || lon > 137.8347) {
            return true;
        }
        return lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /** WGS-84 -> GCJ-02 */
    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** GCJ-02 -> WGS-84 */
    public static double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] g = wgs84ToGcj02(lat, lon);
        return new double[]{lat * 2 - g[0], lon * 2 - g[1]};
    }

    /** GCJ-02 -> BD-09 */
    public static double[] gcj02ToBd09(double lat, double lon) {
        double z = Math.sqrt(lon * lon + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lon) + 0.000003 * Math.cos(lon * X_PI);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    /** BD-09 -> GCJ-02 */
    public static double[] bd09ToGcj02(double lat, double lon) {
        double x = lon - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
    }

    /** WGS-84 -> BD-09 */
    public static double[] wgs84ToBd09(double lat, double lon) {
        double[] gcj = wgs84ToGcj02(lat, lon);
        return gcj02ToBd09(gcj[0], gcj[1]);
    }

    /** BD-09 -> WGS-84 */
    public static double[] bd09ToWgs84(double lat, double lon) {
        double[] gcj = bd09ToGcj02(lat, lon);
        return gcj02ToWgs84(gcj[0], gcj[1]);
    }

    /** 两点球面距离（米），Haversine */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 人类可读距离 */
    public static String formatDistance(double meters) {
        if (meters < 1000) {
            return ((int) Math.round(meters)) + " m";
        }
        return String.format(java.util.Locale.CHINA, "%.1f km", meters / 1000.0);
    }

    /** 十进制度 -> 度分秒，例如 37°04'12.3"N */
    public static String toDms(double value, boolean isLat) {
        char hemi = isLat ? (value >= 0 ? 'N' : 'S') : (value >= 0 ? 'E' : 'W');
        double abs = Math.abs(value);
        int deg = (int) abs;
        int min = (int) ((abs - deg) * 60);
        double sec = ((abs - deg) * 60 - min) * 60;
        return String.format(java.util.Locale.CHINA, "%d°%02d'%04.1f\"%c", deg, min, sec, hemi);
    }
}
