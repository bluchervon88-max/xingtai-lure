package com.xtlure.app.data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线矢量底图：载入与解码（**纯逻辑，不依赖任何 Android API**）。
 *
 * ════════════════════════════════════════════════════════════════════════
 * ★ 为什么从 ui 包里拆出来
 *
 *   原来 `com.xtlure.app.ui.Basemap` 把两件事混在一个类里：
 *     ① 载入文件、按 base-32 解码顶点  —— 纯逻辑
 *     ② 用 Canvas 画 Path              —— UI
 *
 *   后果：离线契约测试编不了它。测试只对 `core/` 和 `util/` 编译
 *   （见 run-tests.ps1），而 Basemap 因为 import 了 android.graphics.Canvas，
 *   被拖进整个 Android UI 依赖，导致"想验证解码是否正确"必须先有一个 Android 环境。
 *
 *   更糟的是我在 iOS 侧已经把两者拆开了（`Basemap.swift` 只有载入解码，
 *   绘制单独做），于是两边的**结构不一致** —— 这本身就违背了
 *   "两侧实现对应、便于对照"的原则。
 *
 *   所以按职责拆开：
 *     `data/BasemapData`  —— 载入 + 解码 + 外接框（本文件，纯逻辑）
 *     `ui/Basemap`        —— 在 Canvas 上绘制
 *
 *   拆分后解码部分能在电脑上直接验证，而且有 25 条契约测试兜着
 *   （期望值由 Python 独立算出，见 tools/compute-basemap-truth.py）。
 *
 * ════════════════════════════════════════════════════════════════════════
 * ★ 为什么自己做底图，而不是接商用地图 SDK
 *
 *   高德 / 百度 / 腾讯的 SDK 都需要三样东西：
 *     ① 申请开发者账号 + 审核 + 包名绑定的 API Key（需要本人去申请）
 *     ② INTERNET 权限，否则一张瓦片都下载不下来
 *     ③ 联网环境，而水库边经常没信号
 *   本 App 的核心卖点恰恰是"不申请网络权限、离线可用"，接 SDK 会把这一点推翻。
 *   需要商用地图时，用「导航」功能跳转到手机上的高德/百度即可 ——
 *   由对方 App 去联网，本 App 依旧不需要任何权限。
 *
 *   数据来源 OpenStreetMap（ODbL 许可，可自由使用，已在关于页署名）。
 *   水系 80KB + 区界 106KB + 河道 33KB ≈ 218KB，比瓦片包小两个数量级，
 *   而且存的是顶点不是位图，缩放不会糊。
 *
 * ════════════════════════════════════════════════════════════════════════
 * ★ 数据格式（由 tools/build-basemap.js 生成）
 *
 *   表头两行：文件标识（XTLURE…）+ 图层名（纯字母）
 *   数据行：`类型|编码的顶点流|local|标注y|标注x|名称`
 *     类型：W 水体面 / B 区县界 / R 河道线
 *
 *   顶点流用 base-32 + 续位标记编码（详见 tools/basemap-codec.js）：
 *     · 每位 5 位数映射到字符 (char)('0' + d)
 *     · 除最后一位外，字符码 +0x20
 *     · 解码时看字符码是否 >= 0x50 判断"后面还有"
 *     · 值映射：非负 v -> 2v，负数 v -> 2|v|+1（最低位是符号位）
 *     · 顶点存的是**与上一点的差值**，相邻点差值小，通常 1~2 个字符
 *
 *   坐标按 1e-5 度量化（约 1 米精度），坐标系 **WGS-84**，
 *   与 App 内钓点坐标一致，绘制前不需要任何转换。
 */
public class BasemapData {

    private static final double SCALE = 1e5;

    private static final int CHAR_BASE = 0x30;
    private static final int CONTINUATION = 0x20;
    private static final int BASE = 32;

    /** 三个底图文件的资源名（不含扩展名），顺序与 iOS 侧一致 */
    public static final String[] FILE_NAMES = {
            "basemap_water.txt", "basemap_river.txt", "basemap_boundary.txt"
    };

    /**
     * 打开一个底图数据流。
     *
     * ★ 这个接口存在的唯一原因：让"从哪读"与"怎么解码"解耦。
     *   Android 上数据在 `assets/` 里，要靠 `Context.getAssets().open(name)`；
     *   电脑上跑测试时数据是普通文件。
     *   把"打开流"抽成接口后，解码逻辑只有一份，两边共用，
     *   测试验证的就是真实产品代码，而不是一份只为测试存在的副本。
     */
    public interface StreamOpener {
        InputStream open(String name) throws Exception;
    }

    /** 一条几何（闭合轮廓或折线） */
    public static final class Shape {
        public float[] lats;
        public float[] lons;
        /** 闭合轮廓 = true（水体面、区县界），折线 = false（河道） */
        public boolean closed;
        /** 水体名 / 区县名（可能为空） */
        public String name = "";
        /** 是否属于邢台市（区县界用来区分本市与周边地市，周边画得更淡） */
        public boolean local;
        /** 是否行政区界（与水体同为闭合面，靠类型字符区分） */
        public boolean isBoundary;
        /** 名称标注位置 */
        public float labelLat, labelLon;
        /** 是否渠道（河道专用） */
        public boolean canal;

        public Shape() {
        }

        /** 顶点数 */
        public int count() {
            return lats == null ? 0 : lats.length;
        }
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
     * 从三个数据流载入。这是唯一的实际载入路径，其余入口都转到这里。
     *
     * 解析约 5.3 万个顶点，耗时几十毫秒。解析失败不影响主流程
     * （地图退化为只有钓点），但会返回原因字符串。
     *
     * @return null 表示成功；否则是错误说明
     */
    public String load(StreamOpener opener) {
        if (loaded) {
            return null;
        }
        try {
            // 顺序：水系 -> 河道 -> 区界。
            // 与 iOS 侧一致，便于两边日志对照。
            vertexCount += loadOne(opener, FILE_NAMES[0], water, true);
            vertexCount += loadOne(opener, FILE_NAMES[1], river, false);
            vertexCount += loadOne(opener, FILE_NAMES[2], boundary, true);
            loaded = true;
            return null;
        } catch (Throwable e) {
            return "底图载入失败：" + e + "。地图将只显示钓点。";
        }
    }

    /**
     * 从目录载入，供离线契约测试使用。
     *
     * @return 顶点总数；失败返回 -1
     */
    public int loadFromDir(final java.io.File dir) {
        String err = load(new StreamOpener() {
            @Override
            public InputStream open(String name) throws Exception {
                return new java.io.FileInputStream(new java.io.File(dir, name));
            }
        });
        return err == null ? vertexCount : -1;
    }

    private int loadOne(StreamOpener opener, String name, List<Shape> target,
                        boolean closed) throws Exception {
        BufferedReader r = null;
        int count = 0;
        try {
            r = new BufferedReader(new InputStreamReader(opener.open(name), "UTF-8"), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                // 表头行：文件标识，或"不含竖线的纯字母行"（图层名）
                if (line.startsWith("XTLURE") || (!line.contains("|") && isAlpha(line))) {
                    continue;
                }
                Shape s = decode(line, closed);
                // 解码失败的行静默跳过：底图是辅助图层，个别几何坏掉不该影响整体
                if (s != null && s.lats != null && s.lats.length >= 2) {
                    target.add(s);
                    count += s.lats.length;
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
    static final class Cursor {
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
    static int readInt(String s, Cursor c) {
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
        // ★ 与编码端互逆：奇数表示负数 v = -(value-1)/2，偶数表示 v = value/2
        //
        //   这里曾经写错成 -(value+1)/2，于是所有负数的恢复值都差 1。
        //   是 tools/verify-codec.js 的整数往返测试抓出来的 ——
        //   所以那个脚本必须留着，不能删。
        return (value & 1) == 1 ? -((value - 1) / 2) : (value / 2);
    }

    public Shape decode(String line, boolean closed) {
        // limit=6：名称里可能有竖线，要保留在最后一个字段里
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

    // ================================================================ 范围

    /** 底图覆盖的地理范围 {minLat, minLon, maxLat, maxLon}，供地图选择初始视野 */
    public double[] bounds() {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        List<Shape>[] groups = new List[]{water, river, boundary};
        for (List<Shape> group : groups) {
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

    /**
     * 一条几何的经纬度外接框 {minLat, minLon, maxLat, maxLon}。
     *
     * 原来是在 UI 的剔除逻辑里顺带按**屏幕坐标**算的。
     * 外接框本身与投影无关，提前按经纬度算好还能复用 ——
     * 同一几何在多帧之间不会变，没必要每帧重算。
     */
    public double[] lonLatBox(Shape s) {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (int i = 0; i < s.lats.length; i++) {
            if (s.lats[i] < minLat) minLat = s.lats[i];
            if (s.lats[i] > maxLat) maxLat = s.lats[i];
            if (s.lons[i] < minLon) minLon = s.lons[i];
            if (s.lons[i] > maxLon) maxLon = s.lons[i];
        }
        return new double[]{minLat, minLon, maxLat, maxLon};
    }

    /** 统计信息，界面上用来确认底图是否真的载入了 */
    public String describe() {
        if (!loaded) {
            return "底图未载入";
        }
        double[] b = bounds();
        return String.format(java.util.Locale.CHINA,
                "水系 %d · 河道 %d · 区县界 %d · 顶点 %d · %.2f~%.2f°N %.2f~%.2f°E",
                water.size(), river.size(), boundary.size(), vertexCount,
                b[0], b[2], b[1], b[3]);
    }
}
