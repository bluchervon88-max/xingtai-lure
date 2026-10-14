package com.xtlure.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.xtlure.app.core.CoordinateTransform;

/**
 * 把钓点坐标送到地图 App。
 *
 * 坐标系转换是这个类存在的唯一理由：
 *   数据库统一存 WGS-84（GPS 原始值），
 *   高德/腾讯用 GCJ-02，百度用 BD-09。
 * 不转换就会偏移 300~600 米 —— 在野外等于找不到钓点。
 */
public final class MapLauncher {

    private MapLauncher() {
    }

    /** 地图 App 选择结果，用于设置页展示 */
    public static boolean isAmapInstalled(Context c) {
        return isPackageInstalled(c, "com.autonavi.minimap");
    }

    public static boolean isBaiduInstalled(Context c) {
        return isPackageInstalled(c, "com.baidu.BaiduMap");
    }

    public static boolean isTencentInstalled(Context c) {
        return isPackageInstalled(c, "com.tencent.map");
    }

    private static boolean isPackageInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 高德

    /** 用高德地图打开（自动处理 WGS84 -> GCJ02） */
    public static boolean openAmap(Context ctx, String title, double wgsLat, double wgsLon) {
        double[] g = CoordinateTransform.wgs84ToGcj02(wgsLat, wgsLon);
        String name = Uri.encode(title == null ? "钓点" : title);

        // 1) 优先官方 scheme，可直接落点并带标题
        String uri = "androidamap://viewMap?sourceApplication=xtlure&poiname=" + name
                + "&lat=" + g[0] + "&lon=" + g[1] + "&dev=0";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage("com.autonavi.minimap");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(ctx, i)) {
            return true;
        }

        // 2) 退化到标准 geo: 协议（高德也注册了这个）
        Intent geo = new Intent(Intent.ACTION_VIEW,
                Uri.parse(String.format(java.util.Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                        g[0], g[1], g[0], g[1], name)));
        geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(ctx, geo);
    }

    /** 用百度地图打开（自动处理 WGS84 -> BD09） */
    public static boolean openBaidu(Context ctx, String title, double wgsLat, double wgsLon) {
        double[] b = CoordinateTransform.wgs84ToBd09(wgsLat, wgsLon);
        String uri = "baidumap://map/marker?location=" + b[0] + "," + b[1]
                + "&title=" + Uri.encode(title == null ? "钓点" : title)
                + "&content=" + Uri.encode("邢台路亚钓点")
                + "&coord_type=bd09ll&src=xtlure";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage("com.baidu.BaiduMap");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(ctx, i);
    }

    /** 用腾讯地图打开 */
    public static boolean openTencent(Context ctx, String title, double wgsLat, double wgsLon) {
        double[] g = CoordinateTransform.wgs84ToGcj02(wgsLat, wgsLon);
        String uri = "qqmap://map/marker?marker=coord:" + g[0] + "," + g[1]
                + ";title:" + Uri.encode(title == null ? "钓点" : title)
                + ";addr:邢台路亚钓点&referer=xtlure";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage("com.tencent.map");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(ctx, i);
    }

    /**
     * 交给系统自己挑地图 App。
     * 这里故意不转坐标系：因为不同接收方期望不同，用 GCJ-02 更通用（国内地图多数如此）。
     */
    public static boolean openGeneric(Context ctx, String title, double wgsLat, double wgsLon) {
        double[] g = CoordinateTransform.wgs84ToGcj02(wgsLat, wgsLon);
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse(String.format(java.util.Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                        g[0], g[1], g[0], g[1], Uri.encode(title == null ? "钓点" : title))));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(ctx, i);
    }

    /** 网页版地图兜底（不需要装任何 App） */
    public static boolean openWebMap(Context ctx, String title, double wgsLat, double wgsLon) {
        double[] g = CoordinateTransform.wgs84ToGcj02(wgsLat, wgsLon);
        String url = "https://uri.amap.com/marker?position=" + g[1] + "," + g[0]
                + "&name=" + Uri.encode(title == null ? "钓点" : title)
                + "&coordinate=gaode&callnative=1";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(ctx, i);
    }

    /**
     * 智能打开：按已安装的地图依次尝试，最后退化到网页。
     * @return 实际使用的通道名，失败返回 null
     */
    public static String smartOpen(Context ctx, String title, double wgsLat, double wgsLon) {
        if (isAmapInstalled(ctx) && openAmap(ctx, title, wgsLat, wgsLon)) {
            return "高德地图";
        }
        if (isBaiduInstalled(ctx) && openBaidu(ctx, title, wgsLat, wgsLon)) {
            return "百度地图";
        }
        if (isTencentInstalled(ctx) && openTencent(ctx, title, wgsLat, wgsLon)) {
            return "腾讯地图";
        }
        if (openGeneric(ctx, title, wgsLat, wgsLon)) {
            return "系统地图";
        }
        if (openWebMap(ctx, title, wgsLat, wgsLon)) {
            return "网页地图";
        }
        return null;
    }

    private static boolean tryStart(Context ctx, Intent i) {
        try {
            if (i.resolveActivity(ctx.getPackageManager()) == null
                    && i.getPackage() == null) {
                return false;
            }
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 复制

    /** 复制多种格式坐标到剪贴板，方便粘到微信/群里 */
    public static void copyCoord(Context ctx, String name, double lat, double lon) {
        double[] g = CoordinateTransform.wgs84ToGcj02(lat, lon);
        double[] b = CoordinateTransform.wgs84ToBd09(lat, lon);
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('\n');
        sb.append("WGS84(GPS原始): ").append(fmt(lat)).append(", ").append(fmt(lon)).append('\n');
        sb.append("GCJ02(高德/腾讯): ").append(fmt(g[0])).append(", ").append(fmt(g[1])).append('\n');
        sb.append("BD09(百度): ").append(fmt(b[0])).append(", ").append(fmt(b[1])).append('\n');
        sb.append("高德链接: https://uri.amap.com/marker?position=").append(g[1]).append(',').append(g[0]);
        copy(ctx, "钓点坐标", sb.toString());
        Toast.makeText(ctx, "已复制三种坐标系坐标", Toast.LENGTH_SHORT).show();
    }

    public static void copy(Context ctx, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
            }
        } catch (Exception ignored) {
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.CHINA, "%.6f", v);
    }
}
