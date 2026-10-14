package com.xtlure.app.ui;

/**
 * 地图视野与投影。
 *
 * ★ 为什么把这段数学单独抽出来
 *   之前它散落在 View 的字段与方法里，比例依赖「plot 尺寸 + zoom + 中心纬度」三个值，
 *   而这三者分别由 setData / 布局 / 绘制三个入口更新，任何一个漏更新，
 *   比例就会停在错误的值上 —— 真机上表现为"缩放到 32× 才勉强能用"。
 *   排查时反复读到互相矛盾的中间状态，光靠 adb 读界面值根本定位不了。
 *
 *   现在这里是一个**纯计算对象**：不引用任何 Android 类，
 *   只依赖构造时传入的视口像素尺寸和缩放倍数。
 *   任何时刻调 pxPerLatDeg() / visible*() 都返回当下正确的值，
 *   也可以在 Node 里逐字复刻做离线断言（见 tools/verify-projection.js）。
 *
 * ★ 比例（本类存在的核心理由）
 *   经纬度不能按 1:1 铺进画框。在邢台（北纬 37.2°）1° 纬度约 111 km，
 *   1° 经度只有 111×cos(37.2°) ≈ 88.6 km —— 相差 1.256 倍。
 *   若两个方向各自拉满画框，等于把地图**横向拉伸 1.256 倍**，形状是歪的，
 *   比例尺也必然错。所以这里保证：
 *
 *       pxPerLonDeg = pxPerLatDeg × cos(centerLat)
 *
 *   即两个方向的**地面米数对应相同像素数**。比例尺只需按纬度换算就准确。
 *
 * ★ 单位约定（写错会让结论完全失真，本文件内统一）
 *   所有 pxPerXxxDeg() 都是「每 **度**」的像素数，不是每 0.01°。
 */
final class GeoViewport {

    /** 每度纬度的米数 */
    static final double METERS_PER_DEG_LAT = 111320.0;

    /** 视口宽度（像素） */
    final float width;
    /** 视口高度（像素） */
    final float height;
    /** 缩放倍数，1× = 东西约 {@link #TARGET_KM_AT_1X} km */
    final float zoom;
    /** 视野中心的纬度 */
    final double centerLat;
    /** 视野中心的经度 */
    final double centerLon;

    /** 1× 时东西方向的可见宽度（km） */
    static final double TARGET_KM_AT_1X = 42.0;
    static final float MIN_ZOOM = 0.25f;
    static final float MAX_ZOOM = 32f;

    GeoViewport(float width, float height, float zoom,
                double centerLat, double centerLon) {
        this.width = Math.max(1f, width);
        this.height = Math.max(1f, height);
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        this.centerLat = centerLat;
        this.centerLon = centerLon;
    }

    GeoViewport withZoom(float z) {
        return new GeoViewport(width, height, z, centerLat, centerLon);
    }

    GeoViewport withCenter(double lat, double lon) {
        return new GeoViewport(width, height, zoom, lat, lon);
    }

    /** 中心纬度处的经度收缩系数 */
    double cosLat() {
        return Math.cos(Math.toRadians(centerLat));
    }

    /**
     * 每度纬度对应的像素数。
     *
     * 推导：让 1× 时东西方向正好是 TARGET_KM_AT_1X 公里。
     *   目标经度跨度(度) = TARGET_KM / (111.32 × cosLat)
     *   该跨度要铺满画框宽      => pxPerLonDeg = width / 目标经度跨度
     *   再由 pxPerLatDeg = pxPerLonDeg / cosLat 得到
     * 合起来：pxPerLatDeg = width / (目标经度跨度 × cosLat)
     */
    float pxPerLatDeg() {
        double targetLonDeg = TARGET_KM_AT_1X / (111.32 * cosLat());
        return (float) (width / (targetLonDeg * cosLat()) * zoom);
    }

    /** 每度经度对应的像素数（含 cos 修正，保证形状不变形） */
    float pxPerLonDeg() {
        return (float) (pxPerLatDeg() * cosLat());
    }

    /** 每像素对应多少米（两个方向相同，这是"不变形"的直接体现） */
    double metersPerPixel() {
        return METERS_PER_DEG_LAT / pxPerLatDeg();
    }

    // ---------------------------------------------------------------- 可见范围

    double minLat() {
        return centerLat - height / 2.0 / pxPerLatDeg();
    }

    double maxLat() {
        return centerLat + height / 2.0 / pxPerLatDeg();
    }

    double minLon() {
        return centerLon - width / 2.0 / pxPerLonDeg();
    }

    double maxLon() {
        return centerLon + width / 2.0 / pxPerLonDeg();
    }

    /** 可见的纬度跨度（度） */
    double visibleLatSpan() {
        return height / pxPerLatDeg();
    }

    /** 可见的经度跨度（度） */
    double visibleLonSpan() {
        return width / pxPerLonDeg();
    }

    // ---------------------------------------------------------------- 投影

    /** 经度 -> 屏幕 x */
    float x(double lon) {
        return (float) (width / 2.0 + (lon - centerLon) * pxPerLonDeg());
    }

    /** 纬度 -> 屏幕 y（北在上，所以纬度越大 y 越小） */
    float y(double lat) {
        return (float) (height / 2.0 - (lat - centerLat) * pxPerLatDeg());
    }

    /** 屏幕 x -> 经度 */
    double lonAt(float screenX) {
        return centerLon + (screenX - width / 2.0) / pxPerLonDeg();
    }

    /** 屏幕 y -> 纬度 */
    double latAt(float screenY) {
        return centerLat - (screenY - height / 2.0) / pxPerLatDeg();
    }

    // ---------------------------------------------------------------- 视野调整

    /**
     * 以某个屏幕点为锚点缩放，返回新的视野。
     *
     * 锚点不动的含义：该屏幕点对应的经纬度在缩放前后保持在同一屏幕位置。
     * 这是捏合缩放"捏哪里放大哪里"的实现基础。
     */
    GeoViewport zoomAround(float newZoom, float anchorX, float anchorY) {
        double anchorLon = lonAt(anchorX);
        double anchorLat = latAt(anchorY);
        GeoViewport scaled = withZoom(newZoom);
        double newCenterLon = anchorLon - (anchorX - scaled.width / 2.0) / scaled.pxPerLonDeg();
        double newCenterLat = anchorLat + (anchorY - scaled.height / 2.0) / scaled.pxPerLatDeg();
        return scaled.withCenter(newCenterLat, newCenterLon);
    }

    /**
     * 把中心限制在给定经纬范围内。
     *
     * 可见范围大于给定范围时，中心直接取范围中点 ——
     * 这样缩到最小时图不会偏在一角，也不会拖出一片空白。
     */
    GeoViewport clampCenter(double bMinLat, double bMinLon, double bMaxLat, double bMaxLon) {
        double halfLat = visibleLatSpan() / 2.0;
        double halfLon = visibleLonSpan() / 2.0;

        double lat;
        if (halfLat * 2 >= bMaxLat - bMinLat) {
            lat = (bMinLat + bMaxLat) / 2;
        } else {
            lat = Math.max(bMinLat + halfLat, Math.min(bMaxLat - halfLat, centerLat));
        }

        double lon;
        if (halfLon * 2 >= bMaxLon - bMinLon) {
            lon = (bMinLon + bMaxLon) / 2;
        } else {
            lon = Math.max(bMinLon + halfLon, Math.min(bMaxLon - halfLon, centerLon));
        }
        return withCenter(lat, lon);
    }

    /** 生成一个以给定经纬范围为中心、1× 缩放的视野 */
    static GeoViewport centeredOn(float width, float height,
                                 double minLat, double minLon,
                                 double maxLat, double maxLon) {
        return new GeoViewport(width, height, 1f,
                (minLat + maxLat) / 2, (minLon + maxLon) / 2);
    }

    /** 视野摘要，界面上用于把"比例对不对"变成可读数字 */
    String describe() {
        return String.format(java.util.Locale.CHINA,
                "%.2f~%.2f°N %.2f~%.2f°E", minLat(), maxLat(), minLon(), maxLon());
    }
}
