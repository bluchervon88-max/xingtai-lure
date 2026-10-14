import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.data.BasemapData;
import com.xtlure.app.ui.GeoViewport;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨语言契约测试 —— 地图投影部分（Java 侧）。
 *
 * ★ 为什么单独一个文件
 *
 *   `testdata/vectors.json` 里有 `mapProjection` 一段，写明了期望的比例关系。
 *   但**两侧都从来没测过它**：
 *     · Java 的 VectorTest 只覆盖 coordinateTransform / distance / format /
 *       sunMoon / theme，跳过了 mapProjection
 *     · Swift 侧同样跳过
 *
 *   契约文件里写着期望值、却没有任何一侧校验，等于这段契约是摆设 ——
 *   一边的投影算错了，另一边的测试照样是绿的。
 *   这比完全不写契约更危险，因为它**看起来**有覆盖。
 *
 *   Swift 侧补齐后立刻发现了两个问题（见下），所以 Java 侧也必须补上，
 *   两边对称才算真的受契约约束。
 *   与 Swift 的对应文件：`ios/Sources/VectorTest/ContractExtra.swift` 的
 *   `runMapProjectionContract`。**改一边必须改另一边。**
 *
 * ★ 补齐过程中暴露的两个问题（记录在此，避免以后再踩）
 *
 *   1. **契约里的容差 1e-9 是错的**
 *      Java 的 `pxPerLatDeg()` 返回 `float`，Swift 的同名属性也是 `Float`。
 *      float 的相对精度上限约 1.2e-7 —— 1e-9 在 float 运算下物理上不可能达到。
 *      实测相对误差 1.94e-08，正是 float 舍入的量级。
 *      已把契约里的 `expectRatioEqualWithin` 从 1e-9 放宽到 1e-6：
 *      仍能抓住任何真实的形变 bug（当年那个是横向拉伸 1.256 倍，差 6 个数量级），
 *      但不会因为浮点精度而误报。
 *
 *   2. **TARGET_KM_AT_1X 是"东西方向"的宽度，不是南北**
 *      Java 原版注释写得很明确：`1× 时东西方向的可见宽度（km）`。
 *      推导链是 TARGET_KM_AT_1X -> 目标经度跨度 -> pxPerLatDeg
 *      （让 42km 正好铺满画框的**宽度**）。
 *      所以校验要算可见**经度**跨度换算成米（乘 cos 纬度）。
 *
 * ★ 关于可见性
 *   为了能从这个默认包的测试访问 `com.xtlure.app.ui.GeoViewport`，
 *   该类的相关成员已从包私有改为 public（改动处有说明）。
 */
final class VectorTestMap {

    private VectorTestMap() {
    }

    /** 与 Swift 侧一致的基准画框。契约里写明"像素尺寸由各平台自行推导"，
     *  所以能跨平台比较的只有比例关系，固定画框才能直接对比。 */
    private static final float W = 390f;
    private static final float H = 600f;

    /** 测试结果计数。返回给主测试累加，避免两个文件各维护一套计数器。 */
    static final class Result {
        int pass = 0;
        int fail = 0;
    }

    private static void check(Result r, String name, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (ok) {
            r.pass++;
        } else {
            r.fail++;
        }
    }

    /**
     * 跑地图投影契约。
     *
     * @param v 已载入的 vectors.json 读取器（与 VectorTest 共用同一个实例）
     */
    static Result run(VectorReader v) {
        Result r = new Result();
        System.out.println("\n=== 契约: mapProjection ===");

        Map<String, Object> mp = v.section("mapProjection");
        if (mp == null) {
            check(r, "mapProjection 段存在", false, "契约文件里找不到这一段");
            return r;
        }

        // ---- 1x 缩放时的目标视野宽度：东西方向（经度）
        double wantKm = VectorReader.num(mp, "targetKmAt1x", Double.NaN);
        if (!Double.isNaN(wantKm)) {
            GeoViewport vp = new GeoViewport(W, H, 1f, 37.225, 114.775);
            double metersPerDegLon = GeoViewport.METERS_PER_DEG_LAT * vp.cosLat();
            double km = vp.visibleLonSpan() * metersPerDegLon / 1000.0;
            double rel = Math.abs(km - wantKm) / wantKm;
            check(r, "targetKmAt1x（东西向可见宽度）", rel < 0.02,
                    String.format(Locale.CHINA,
                            "%.2f km  期望约 %.1f（偏差 %.1f%%）", km, wantKm, rel * 100));
            check(r, "GeoViewport.TARGET_KM_AT_1X 常量",
                    Math.abs(GeoViewport.TARGET_KM_AT_1X - wantKm) < 0.01,
                    String.format(Locale.CHINA, "%.2f  期望 %.2f",
                            GeoViewport.TARGET_KM_AT_1X, wantKm));
        }

        // ---- 缩放范围
        double wantMin = VectorReader.num(mp, "minZoom", Double.NaN);
        if (!Double.isNaN(wantMin)) {
            check(r, "minZoom", Math.abs(GeoViewport.MIN_ZOOM - wantMin) < 1e-6,
                    String.format(Locale.CHINA, "%.2f  期望 %.2f",
                            (double) GeoViewport.MIN_ZOOM, wantMin));
        }
        double wantMax = VectorReader.num(mp, "maxZoom", Double.NaN);
        if (!Double.isNaN(wantMax)) {
            check(r, "maxZoom", Math.abs(GeoViewport.MAX_ZOOM - wantMax) < 1e-6,
                    String.format(Locale.CHINA, "%.0f  期望 %.0f",
                            (double) GeoViewport.MAX_ZOOM, wantMax));
        }

        // ---- 各纬度下两个方向的像素比例必须一致
        for (Map<String, Object> c : v.list("mapProjection", "aspectRatios")) {
            String id = VectorReader.id(c);
            double lat = VectorReader.num(c, "centerLat", 37.225);
            double zoom = VectorReader.num(c, "zoom", 1.0);
            double tol = VectorReader.num(c, "expectRatioEqualWithin", 1e-6);

            GeoViewport vp = new GeoViewport(W, H, (float) zoom, lat, 114.775);

            // (1) 契约写明的比例关系：pxPerLonDeg / pxPerLatDeg == cos(纬度)
            //
            // 这是"不变形"的充要条件：经度方向每度对应的像素数要按纬度余弦缩小，
            // 两个方向的地面距离才一致。
            //
            // 容差完全由契约决定，这里不要再夹一个更小的下限 ——
            // Swift 侧第一版写成 max(tol, 1e-9)，把契约里本该生效的 1e-6
            // 又压回了 1e-9，于是必然误报。
            double ratio = (double) vp.pxPerLonDeg() / (double) vp.pxPerLatDeg();
            double expect = Math.cos(lat * Math.PI / 180.0);
            double rel = Math.abs(ratio - expect) / expect;
            check(r, id, rel <= tol,
                    String.format(Locale.CHINA,
                            "pxLon/pxLat = %.10f  期望 cos(%.3f°) = %.10f  相对差 %.2e（上限 %.0e）",
                            ratio, lat, expect, rel, tol));

            // (2) 交叉验证：可见经度跨度换算成米（乘 cos 纬度）
            //     与可见纬度跨度换算成米的比值，应当等于画框宽高比。
            //     两个方向只要有一处算错，这个等式就不成立。
            double metersPerDegLon = GeoViewport.METERS_PER_DEG_LAT * vp.cosLat();
            double widthMeters = vp.visibleLonSpan() * metersPerDegLon;
            double heightMeters = vp.visibleLatSpan() * GeoViewport.METERS_PER_DEG_LAT;
            double frameAspect = (double) W / (double) H;
            double geoAspect = widthMeters / heightMeters;
            double aspectRel = Math.abs(geoAspect - frameAspect) / frameAspect;
            check(r, id + " 地面宽高比 = 画框宽高比", aspectRel < 1e-6,
                    String.format(Locale.CHINA, "地面 %.6f  画框 %.6f  相对差 %.2e",
                            geoAspect, frameAspect, aspectRel));

            // (3) metersPerPixel 只有一个值 —— 这本身就是"两方向一致"的设计体现。
            //     确认它落在合理量级
            //     （37°N 附近、600px 高看约 42km -> 每像素约百来米）。
            double mpp = vp.metersPerPixel();
            check(r, id + " metersPerPixel 量级", mpp > 10 && mpp < 500,
                    String.format(Locale.CHINA, "%.1f m/px", mpp));
        }

        return r;
    }

    /**
     * 禁渔期判定契约（Java 侧）。
     *
     * ★ 为什么这段必须有测试
     *
     *   判错的后果不是显示难看，而是**用户可能在禁渔期下竿** —— 这是合规问题。
     *
     *   而且这段逻辑有两个容易写错的地方：
     *     1. 边界日：区间是闭区间（月*100+日 的整数比较），起始日和结束日
     *        当天都算禁渔期。差一天就是"合法/违法"的区别。
     *     2. 跨年：禁渔期是 5~7 月，所以在 8~12 月要算到**次年**的 5 月 16 日。
     *        这个 +1 年的分支最容易漏，而漏了在禁渔期之外的大部分时间里都不会显形。
     *
     * @param v      契约读取器
     * @param reader 用于取整数字段（waterType）
     */
    static Result runClosedSeason(VectorReader v, VectorReader reader) {
        Result r = new Result();
        System.out.println("\n=== 契约: closedSeason ===");

        Map<String, Object> cs = v.section("closedSeason");
        if (cs == null) {
            check(r, "closedSeason 段存在", false, "契约文件里找不到这一段");
            return r;
        }

        // ---- 日期区间判定
        for (Map<String, Object> c : v.list("closedSeason", "inSeason")) {
            String id = VectorReader.id(c);
            String date = VectorReader.str(c, "date", "");
            boolean want = VectorReader.bool(c, "expect", false);

            Calendar cal = calendarOf(date);
            if (cal == null) {
                check(r, id + " 日期解析", false, "无法解析 '" + date + "'");
                continue;
            }
            boolean got = FishingSpot.inClosedSeason(cal);
            check(r, id, got == want,
                    date + " -> " + got + "  期望 " + want);
        }

        // ---- 水域类型是否受约束（黑坑/池塘豁免）
        for (Map<String, Object> c : v.list("closedSeason", "subjectTo")) {
            String id = VectorReader.id(c);
            int type = VectorReader.intVal(c, "waterType", -1);
            boolean want = VectorReader.bool(c, "expect", false);

            FishingSpot s = new FishingSpot();
            s.setWaterType(type);
            boolean got = s.subjectToClosedSeason();
            check(r, id, got == want,
                    "水域类型 " + type + " -> " + got + "  期望 " + want
                            + (want ? "" : "（人工水域豁免）"));
        }

        // ---- 距禁渔期开始的天数
        for (Map<String, Object> c : v.list("closedSeason", "daysUntil")) {
            String id = VectorReader.id(c);
            String date = VectorReader.str(c, "date", "");
            int want = (int) VectorReader.num(c, "expect", -999);

            Calendar cal = calendarOf(date);
            if (cal == null) {
                check(r, id + " 日期解析", false, "无法解析 '" + date + "'");
                continue;
            }
            int got = FishingSpot.daysUntilClosedSeason(cal);
            check(r, id, got == want,
                    date + " -> " + got + " 天  期望 " + want);
        }

        // ---- 常量与官方文本
        //
        // 文本必须与官方通告一致。「一人一杆一线一钩」那种网上流传但**查不到
        // 官方条文**的说法，早先版本引用过、后来撤掉了，这里锁住防止再被加回去。
        check(r, "禁渔期起始常量",
                FishingSpot.CLOSED_MONTH_FROM == 5 && FishingSpot.CLOSED_DAY_FROM == 16,
                FishingSpot.CLOSED_MONTH_FROM + "月" + FishingSpot.CLOSED_DAY_FROM + "日");
        check(r, "禁渔期结束常量",
                FishingSpot.CLOSED_MONTH_TO == 7 && FishingSpot.CLOSED_DAY_TO == 31,
                FishingSpot.CLOSED_MONTH_TO + "月" + FishingSpot.CLOSED_DAY_TO + "日");
        check(r, "禁渔期文本与常量一致",
                FishingSpot.CLOSED_SEASON_TEXT.contains("5月16日")
                        && FishingSpot.CLOSED_SEASON_TEXT.contains("7月31日"),
                FishingSpot.CLOSED_SEASON_TEXT);
        check(r, "规则引用不含未经证实的说法",
                !FishingSpot.CLOSED_SEASON_RULE.contains("一人一杆一线一钩"),
                "官方条文里查不到「一人一杆一线一钩」，不应作为规定引用");

        return r;
    }

    /**
     * 由 "yyyy-MM-dd" 构造 Calendar。
     *
     * 刻意把时刻清零：判定只看日期，如果带上当前时刻，
     * 在时区边界上可能把日期算成前后一天，导致跨时区的测试结果不一致。
     */
    private static Calendar calendarOf(String ymd) {
        int[] p = VectorReader.parseDateTime(ymd);
        if (p == null || p.length < 3) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(p[0], p[1] - 1, p[2]);
        return c;
    }

    /**
     * 离线矢量底图的解码契约（Java 侧）。
     *
     * ★ 期望值来自哪里，为什么重要
     *
     *   `vectors.json` 里 basemap 段的期望值是用 **Python 独立算出来的**
     *   （`tools/compute-basemap-truth.py`），不是从 Java 实现里读出来的。
     *
     *   如果拿 Java 的结果当期望值，"测试通过"只证明 Swift 与 Java 一致，
     *   **不证明两者都解对了** —— 两边完全可能用同样的方式理解错格式。
     *   三个实现互相印证，比两个互相印证强得多。
     *
     * ★ 为什么不能只比几何数和顶点数
     *
     *   计数对但坐标错位是完全可能的：差值累加时起点写错、
     *   经纬度顺序写反、符号映射差 1 —— 这些都不会改变顶点总数。
     *   所以契约里还锁了第一个几何的**前 4 个顶点坐标**，逐点比对。
     *
     * @param v   契约读取器
     * @param dir 存放 basemap_*.txt 的目录（工程的 assets/）
     */
    static Result runBasemap(VectorReader v, java.io.File dir) {
        Result r = new Result();
        System.out.println("\n=== 契约: basemap ===");

        Map<String, Object> bm = v.section("basemap");
        if (bm == null) {
            check(r, "basemap 段存在", false, "契约文件里找不到这一段");
            return r;
        }

        // 载入**真实的产品代码**，不是测试专用的副本
        BasemapData map = new BasemapData();
        int vertices = map.loadFromDir(dir);
        if (vertices < 0) {
            check(r, "底图载入", false, "loadFromDir 失败，目录 " + dir);
            return r;
        }
        check(r, "底图载入成功", true, vertices + " 个顶点，来自 " + dir.getName());

        // ---- 三个图层的几何数与顶点数
        String[][] layers = {{"water", "水系"}, {"river", "河道"}, {"boundary", "区县界"}};
        for (String[] layer : layers) {
            String key = layer[0];
            Map<String, Object> want = VectorReader.child(bm, key);
            if (want == null) {
                continue;
            }
            java.util.List<BasemapData.Shape> got =
                    key.equals("water") ? map.water()
                            : key.equals("river") ? map.river() : map.boundary();

            int wantShapes = (int) VectorReader.num(want, "shapes", -1);
            check(r, layer[1] + " 几何数", got.size() == wantShapes,
                    got.size() + " 个  期望 " + wantShapes);

            int vsum = 0;
            for (BasemapData.Shape s : got) {
                vsum += s.lats.length;
            }
            int wantV = (int) VectorReader.num(want, "vertices", -1);
            check(r, layer[1] + " 顶点数", vsum == wantV,
                    vsum + " 个  期望 " + wantV);

            // 闭合标志：河道是折线，其余是闭合面
            boolean expectClosed = !key.equals("river");
            boolean allMatch = true;
            for (BasemapData.Shape s : got) {
                if (s.closed != expectClosed) {
                    allMatch = false;
                    break;
                }
            }
            check(r, layer[1] + " 闭合标志正确", allMatch,
                    expectClosed ? "全部应为闭合面" : "全部应为折线");
        }

        // ---- 总顶点数
        int wantTotal = (int) VectorReader.num(bm, "totalVertices", -1);
        check(r, "顶点总数", map.vertexCount() == wantTotal,
                map.vertexCount() + "  期望 " + wantTotal);

        // ---- 经纬度外接框
        Map<String, Object> bounds = VectorReader.child(bm, "bounds");
        if (bounds != null) {
            double tol = VectorReader.num(bounds, "tol", 0.0002);
            double[] got = map.bounds();   // {minLat, minLon, maxLat, maxLon}
            String[] names = {"minLat", "minLon", "maxLat", "maxLon"};
            for (int i = 0; i < 4; i++) {
                double wantVal = VectorReader.num(bounds, names[i], Double.NaN);
                if (Double.isNaN(wantVal)) {
                    continue;
                }
                check(r, "外接框 " + names[i], Math.abs(got[i] - wantVal) < tol,
                        String.format(Locale.CHINA, "%.4f  期望 %.4f ±%.4f",
                                got[i], wantVal, tol));
            }
        }

        // ---- 逐点核对第一个水体几何
        //
        // 这一步才是真正能抓住"坐标错位"的检查。
        Map<String, Object> sample = VectorReader.child(bm, "sample");
        if (sample != null && !map.water().isEmpty()) {
            BasemapData.Shape s0 = map.water().get(0);
            int wantN = (int) VectorReader.num(sample, "vertexCount", -1);
            check(r, "样本顶点数", s0.lats.length == wantN,
                    s0.lats.length + "  期望 " + wantN);

            String wantName = VectorReader.str(sample, "name", "");
            check(r, "样本名称", s0.name.equals(wantName),
                    "'" + s0.name + "'  期望 '" + wantName + "'");

            double stol = VectorReader.num(sample, "tol", 0.00002);
            java.util.List<double[]> wantVerts = VectorReader.pairs(sample, "vertices");
            for (int i = 0; i < wantVerts.size() && i < s0.lats.length; i++) {
                double[] w = wantVerts.get(i);
                double dLat = Math.abs(s0.lats[i] - w[0]);
                double dLon = Math.abs(s0.lons[i] - w[1]);
                check(r, "样本顶点" + i, dLat < stol && dLon < stol,
                        String.format(Locale.CHINA, "%.5f,%.5f  期望 %.5f,%.5f",
                                s0.lats[i], s0.lons[i], w[0], w[1]));
            }

            boolean wantClosed = VectorReader.bool(sample, "closed", true);
            check(r, "样本闭合标志", s0.closed == wantClosed,
                    s0.closed + "  期望 " + wantClosed);
        }

        return r;
    }
}
