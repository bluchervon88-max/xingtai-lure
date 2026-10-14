package com.xtlure.app.ui;

/**
 * 钓点列表页需要的宿主能力。
 *
 * 独立文件，避免 SpotListFragment 与 MainActivity 之间的编译期循环引用。
 */
public interface SpotListHost {

    /** 上次定位的纬度，未定位返回 NaN */
    double userLat();

    /** 上次定位的经度，未定位返回 NaN */
    double userLon();

    /** 钓点数据发生变化时通知宿主刷新顶栏统计 */
    void onSpotsChanged();

    /** 打开钓点详情 */
    void openSpotDetail(long spotId);
}
