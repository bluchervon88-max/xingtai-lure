package com.xtlure.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线矢量底图。
 *
 * ★ 为什么自己做底图，而不是接商用地图 SDK
 *   高德 / 百度 / 腾讯的 Android SDK 都需要三样东西：
 *     ① 申请开发者账号 + 审核 + 包名绑定的 API Key（需要你本人去申请）
 *     ② INTERNET 权限，否则一张瓦片都下载不下来
 *     ③ 联网环境，而水库边经常没信号
 *   本 App 的核心卖点恰恰是"不申请网络权限、离线可用"，接 SDK 会把这一点推翻。
 *   需要商用地图时，用「导航」功能跳转到手机上的高德/百度即可 ——
 *   由对方 App 去联网，本 App 依旧不需要任何权限。
 *
 *   所以走另一条路：把真实地理数据编译进 APK，本地矢量绘制。
 *   · 数据来源：OpenStreetMap（ODbL 许可，可自由使用，已在关于页署名）
 *   · 体积：水系 76KB + 区县界 102KB + 河道 30KB ≈ 208KB，比瓦片包小两个数量级
 *   · 缩放不会糊：存的是顶点，不是位图
 *
 * ★ 数据格式（tools/build-basemap.js 生成）
 *   表头两行：文件标识 + 图层名
 *   数据行：`编码的顶点流` 或 `编码的顶点流|元数据…`
 *
 *   顶点流用 base-32 + 续位标记编码（详见 tools/basemap-codec.js）：
 *     · 每位 5 位数映射到字符 (char)('0' + d)
 *     · 除最后一位外，字符码 +0x20
 *     · 解码时看字符码是否 >= 0x50 判断"后面还有"
 *     · 值映射：非负 v -> 2v，负数 v -> 2|v|+1
 *     · 顶点用「与上一点的差值」存储，相邻点差值小，通常 1~2 个字符
 *
 *   坐标按 1e-5 度量化（约 1 米精度），坐标系 **WGS-84**，
 *   与 App 内钓点坐标一致，绘制前不需要任何转换。
 */
public class Basemap {

    private static final String TAG = "Basemap";
    private static final double SCALE = 1e5;

    private static final int CHAR_BASE = 0x30;
    private static final int CONTINUATION = 0x20;
    private static final int BASE = 32;

    /** 一条几何（闭合轮廓或折线） */
    public static final class Shape {
        public float[] lats;
        public float[] lons;
        /** 闭合轮廓 = true，折线 = false */
        public boolean closed;
        /** 水体名 / 区县名（可能为空） */
        public String name = "";
        /** 是否属于邢台市（区县界用来区分本市与周边地市） */
        public boolean local;
        /** 是否是行政区界（与水体同为闭合面，靠类型字符区分） */
        public boolean isBoundary;
        /** 名称标注位置 */
        public float labelLat, labelLon;
        /** 是否渠道（河道专用） */
        public boolean canal;
        /** 外接框（屏幕坐标），用于剔除 */
        float minX, maxX, minY, maxY;
    }

    private final List<Shape> water = new ArrayList<>();
    private final List<Shape> boundary = new ArrayList<>();
    private final List<Shape> river = new ArrayList<>();
    private boolean loaded;
    private int vertexCount;

    public List<Shape> water() {
        return water;
    }

    public List<Shape> boundary() {
        return boundary;
    }

    public List<Shape> river() {
        return river;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ================================================================ 载入

    /**
     * 从 assets 载入全部底图数据。
     *
     * 解析约 5.3 万个顶点。解析失败不影响主流程（地图退化为只有钓点），
     * 但会把原因写进日志，便于定位。
     */
    public void load(Context ctx) {
        if (loaded) {
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            vertexCount += loadFile(ctx, "basemap_water.txt", water, true);
            vertexCount += loadFile(ctx, "basemap_river.txt", river, false);
            vertexCount += loadFile(ctx, "basemap_boundary.txt", boundary, true);
            Log.i(TAG, "底图载入完成: 水系 " + water.size() + " 个, 河道 " + river.size()
                    + " 条, 区县界 " + boundary.size() + " 个, 顶点 " + vertexCount
                    + ", 耗时 " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable e) {
            Log.e(TAG, "底图载入失败，地图将只显示钓点", e);
        }
        loaded = true;
    }

    private int loadFile(Context ctx, String asset, List<Shape> target, boolean closed)
            throws Exception {
        BufferedReader r = null;
        int count = 0;
        try {
            r = new BufferedReader(new InputStreamReader(ctx.getAssets().open(asset), "UTF-8"),
                    1 << 16);
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isEmpty()) {
                    continue;
                }
                // 表头行：文件标识 / 图层名，都是纯字母
                if (line.startsWith("XTLURE") || !line.contains("|") && isAlpha(line)) {
                    continue;
                }
                Shape s = decode(line, closed);
                if (s != null && s.lats != null && s.lats.length >= 2) {
                    target.add(s);
                    count += s.lats.length;
                } else {
                    Log.w(TAG, asset + " 第 " + lineNo + " 行解码失败，已跳过");
                }
            }
        } finally {
            if (r != null) {
                r.close();
            }
        }
        return count;
    }

    private static boolean isAlpha(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return s.length() > 0;
    }

    // ================================================================ 解码

    /** 解码游标 */
    private static final class Cursor {
        final String s;
        int i;

        Cursor(String s) {
            this.s = s;
        }
    }

    /**
     * 解码一行。
     *
     * 行格式：`类型|顶点流|local|标注y|标注x|名称`
     * 类型：W 水体面 / B 区县界 / R 河道线
     *
     * 顶点流的解码与 tools/basemap-codec.js 的 decodeInt 严格对应：
     * 逐字符读，字符码 >= 0x50 表示"后面还有"；值的最低位是符号位。
     */
    private static int readInt(String s, Cursor c) {
        int value = 0;
        int pow = 1;
        while (c.i < s.length()) {
            int code = s.charAt(c.i++);
            boolean more = code >= CHAR_BASE + CONTINUATION;
            int digit = code - CHAR_BASE - (more ? CONTINUATION : 0);
            value += digit * pow;
            pow *= BASE;
            if (!more) {
                break;
            }
        }
        // 与编码端互逆：奇数表示负数 v = -(value-1)/2，偶数表示 v = value/2
        return (value & 1) == 1 ? -((value - 1) / 2) : (value / 2);
    }

    private Shape decode(String line, boolean closed) {
        String[] parts = line.split("\\|", 6);
        if (parts.length < 2) {
            return null;
        }
        char type = parts[0].isEmpty() ? '?' : parts[0].charAt(0);
        String body = parts[1];

        Cursor c = new Cursor(body);
        int n = readInt(body, c);
        if (n <= 0 || n > 20000) {
            return null;
        }

        float[] lats = new float[n];
        float[] lons = new float[n];
        int px = 0, py = 0;
        for (int i = 0; i < n; i++) {
            px += readInt(body, c);
            py += readInt(body, c);
            lons[i] = (float) (px / SCALE);
            lats[i] = (float) (py / SCALE);
        }

        Shape shape = new Shape();
        shape.lats = lats;
        shape.lons = lons;
        // 河道是折线，其余闭合
        shape.closed = (type != 'R');

        if (parts.length >= 3) {
            shape.local = "1".equals(parts[2]);
        }
        if (parts.length >= 4 && !parts[3].isEmpty()) {
            shape.labelLat = parseIntSafe(parts[3]) / (float) SCALE;
        } else {
            shape.labelLat = lats[0];
        }
        if (parts.length >= 5 && !parts[4].isEmpty()) {
            shape.labelLon = parseIntSafe(parts[4]) / (float) SCALE;
        } else {
            shape.labelLon = lons[0];
        }
        if (parts.length >= 6) {
            shape.name = parts[5];
        }
        // 区分水体与区县界：都可能是闭合面，靠类型字符判断
        shape.isBoundary = (type == 'B');
        return shape;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ================================================================ 绘制

    /** 投影接口：把经纬度变成屏幕坐标。缩放平移由调用方的画布变换统一处理 */
    public interface Projector {
        float x(double lon);

        float y(double lat);
    }

    private final Path path = new Path();
    private float viewW = 1, viewH = 1;
    private float pad = 60;

    public void setViewport(int w, int h) {
        this.viewW = Math.max(1, w);
        this.viewH = Math.max(1, h);
    }

    /**
     * 绘制底图。
     *
     * @param baseColor 陆地底色
     * @param waterColor 水体填充
     * @param riverColor 河道线
     * @param borderMine 邢台本市行政区界
     * @param borderOther 周边地市界（画得更淡）
     */
    public void draw(Canvas canvas, Projector proj, Paint fill, Paint stroke, Paint textPaint,
                     int waterColor, int riverColor, int borderMine, int borderOther,
                     boolean showLabels, float density) {
        if (!loaded) {
            return;
        }

        // ---- 河道（最底层，河在陆地之上、水体之下）
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, 1.2f * density));
        stroke.setColor(riverColor);
        stroke.setPathEffect(null);
        for (Shape s : river) {
            if (!cull(s, proj)) {
                continue;
            }
            buildPath(s, proj, false);
            canvas.drawPath(path, stroke);
        }

        // ---- 水体（面）
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(waterColor);
        for (Shape s : water) {
            if (!cull(s, proj)) {
                continue;
            }
            buildPath(s, proj, true);
            canvas.drawPath(path, fill);
        }

        // ---- 行政区界（虚线）
        stroke.setStrokeWidth(Math.max(1f, 1f * density));
        for (Shape s : boundary) {
            if (!cull(s, proj)) {
                continue;
            }
            buildPath(s, proj, true);
            stroke.setColor(s.local ? borderMine : borderOther);
            canvas.drawPath(path, stroke);
        }

        // ---- 名称标注
        if (showLabels) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            // 水体名（只标有名字的）
            textPaint.setColor(waterColor);
            for (Shape s : water) {
                if (s.name.isEmpty() || !cull(s, proj)) {
                    continue;
                }
                canvas.drawText(s.name, proj.x(s.labelLon), proj.y(s.labelLat), textPaint);
            }
            // 区县名：只标本市的，避免周边地市把画面挤满
            textPaint.setColor(borderMine);
            for (Shape s : boundary) {
                if (!s.local || s.name.isEmpty() || !cull(s, proj)) {
                    continue;
                }
                canvas.drawText(s.name, proj.x(s.labelLon), proj.y(s.labelLat), textPaint);
            }
        }
    }

    private void buildPath(Shape s, Projector proj, boolean close) {
        path.reset();
        int n = s.lats.length;
        path.moveTo(proj.x(s.lons[0]), proj.y(s.lats[0]));
        for (int i = 1; i < n; i++) {
            path.lineTo(proj.x(s.lons[i]), proj.y(s.lats[i]));
        }
        if (close) {
            path.close();
        }
    }

    /**
     * 屏幕外剔除。
     *
     * 底图有 889 个几何，逐个绘制的话每帧要建近千条 Path。
     * 先算外接框，完全在屏幕外的直接跳过 —— 缩放到市级视野时能省掉大半。
     */
    private boolean cull(Shape s, Projector proj) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < s.lats.length; i++) {
            float x = proj.x(s.lons[i]);
            float y = proj.y(s.lats[i]);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        s.minX = minX;
        s.maxX = maxX;
        s.minY = minY;
        s.maxY = maxY;
        return maxX >= -pad && minX <= viewW + pad && maxY >= -pad && minY <= viewH + pad;
    }

    /** 底图覆盖的地理范围，供地图选择初始视野 */
    public double[] bounds() {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (List<Shape> group : new List[]{water, river, boundary}) {
            for (Shape s : group) {
                if (s.lats == null) {
                    continue;
                }
                for (int i = 0; i < s.lats.length; i++) {
                    if (s.lats[i] < minLat) minLat = s.lats[i];
                    if (s.lats[i] > maxLat) maxLat = s.lats[i];
                    if (s.lons[i] < minLon) minLon = s.lons[i];
                    if (s.lons[i] > maxLon) maxLon = s.lons[i];
                }
            }
        }
        return new double[]{minLat, minLon, maxLat, maxLon};
    }
}
