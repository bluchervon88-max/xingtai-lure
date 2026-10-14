import com.xtlure.app.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 跨平台一致性契约的读取器。
 *
 * 期望值集中在 testdata/vectors.json，Java 与 Swift 两边都从这里读，
 * 而不是各自在测试里写一份 —— 否则"改了 Java 忘了改 Swift"这类漂移
 * 会让两边测试同时变绿，却算出不同结果。
 *
 * 这里刻意复用项目自己的 Json 解析器（src/com/xtlure/app/util/Json.java）：
 * 它零 Android 依赖、测试时本来就在 classpath 上，不用为测试再引一个 JSON 库。
 *
 * 用法：
 *   VectorReader v = VectorReader.load();
 *   for (Map&lt;String, Object&gt; c : v.list("coordinateTransform")) { ... }
 */
public final class VectorReader {

    private final Map<String, Object> root;

    private VectorReader(Map<String, Object> root) {
        this.root = root;
    }

    /** 从工程根目录下的 testdata/vectors.json 载入 */
    public static VectorReader load() {
        File f = findVectorsFile();
        if (f == null) {
            throw new IllegalStateException(
                    "找不到 testdata/vectors.json —— 契约文件缺失，无法验证跨平台一致性");
        }
        try {
            String text = readUtf8(f);
            Map<String, Object> m = Json.obj(Json.parse(text));
            if (m == null) {
                throw new IllegalStateException("vectors.json 顶层不是对象");
            }
            return new VectorReader(m);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + f.getAbsolutePath() + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向上逐级查找 testdata/vectors.json。
     *
     * 测试可能从工程根目录、也可能从 build/ 子目录被调用，
     * 写死相对路径会在其中一种情况下找不到。
     */
    private static File findVectorsFile() {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(new File(dir, "testdata"), "vectors.json");
            if (f.isFile()) {
                return f;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String readUtf8(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ---------------------------------------------------------------- 取值

    /** 取一个子对象 */
    public Map<String, Object> section(String key) {
        return Json.child(root, key);
    }

    /** 取一个整数，例如 closedSeason.subjectTo 里的 waterType */
    public static int intVal(Map<String, Object> v, String key, int def) {
        Object o = v.get(key);
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return def;
    }

    /**
     * 取嵌套在给定对象里的子对象。
     *
     * 与 `section(key)` 的区别：那个从**顶层**取，这个从任意对象取。
     * 例如 basemap.water / basemap.bounds / basemap.sample 都是顶层的
     * basemap 下面的子对象。
     */
    public static Map<String, Object> child(Map<String, Object> v, String key) {
        if (v == null) {
            return null;
        }
        return Json.obj(v.get(key));
    }

    /**
     * 取一个「二元数组的数组」，例如 basemap.sample.vertices = [[lat,lon], ...]。
     *
     * 用 double[] 而不是自定义类型：调用方几乎总是立刻拿两个数去比较，
     * 包一层结构体只会让测试代码更长。
     */
    public static List<double[]> pairs(Map<String, Object> v, String key) {
        List<double[]> out = new java.util.ArrayList<double[]>();
        if (v == null) {
            return out;
        }
        List<Object> raw = Json.arr(v.get(key));
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            List<Object> inner = Json.arr(o);
            if (inner != null && inner.size() >= 2) {
                Double a = toDouble(inner.get(0));
                Double b = toDouble(inner.get(1));
                if (a != null && b != null) {
                    out.add(new double[]{a, b});
                }
            }
        }
        return out;
    }

    /** 取一个对象数组，例如 coordinateTransform / distance */
    public List<Map<String, Object>> list(String key) {
        List<Object> raw = Json.arr(root.get(key));
        List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>();
        if (raw != null) {
            for (Object o : raw) {
                Map<String, Object> m = Json.obj(o);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** 取嵌套对象里的对象数组，例如 sunMoon.sunriseSunset */
    public List<Map<String, Object>> list(String parent, String key) {
        Map<String, Object> p = section(parent);
        if (p == null) {
            return new java.util.ArrayList<Map<String, Object>>();
        }
        List<Object> raw = Json.arr(p.get(key));
        List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>();
        if (raw != null) {
            for (Object o : raw) {
                Map<String, Object> m = Json.obj(o);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    public static String id(Map<String, Object> v) {
        return Json.str(v, "id", "?");
    }

    public static String desc(Map<String, Object> v) {
        return Json.str(v, "desc", "");
    }

    /** 取 [lat, lon] 这样的二元组 */
    public static double[] pair(Map<String, Object> v, String key) {
        List<Object> a = Json.arr(v.get(key));
        if (a == null || a.size() < 2) {
            return null;
        }
        return new double[]{toDouble(a.get(0)), toDouble(a.get(1))};
    }

    /** 取 [下限, 上限] */
    public static double[] range(Map<String, Object> v, String key) {
        return pair(v, key);
    }

    /** 取 [[下限,上限],[下限,上限]] 这类"命中任一区间即可"的约束 */
    public static double[][] ranges(Map<String, Object> v, String key) {
        List<Object> outer = Json.arr(v.get(key));
        if (outer == null) {
            return null;
        }
        double[][] out = new double[outer.size()][];
        for (int i = 0; i < outer.size(); i++) {
            List<Object> inner = Json.arr(outer.get(i));
            if (inner == null || inner.size() < 2) {
                return null;
            }
            out[i] = new double[]{toDouble(inner.get(0)), toDouble(inner.get(1))};
        }
        return out;
    }

    public static double num(Map<String, Object> v, String key, double def) {
        return Json.num(v, key, def);
    }

    public static boolean has(Map<String, Object> v, String key) {
        return v.containsKey(key) && v.get(key) != null;
    }

    public static boolean bool(Map<String, Object> v, String key, boolean def) {
        return Json.bool(v, key, def);
    }

    public static String str(Map<String, Object> v, String key, String def) {
        return Json.str(v, key, def);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** 把 "2026-06-21" 或 "2026-01-19T12:00:00" 拆成 {年, 月, 日, 时, 分} */
    public static int[] parseDateTime(String s) {
        String datePart = s;
        double hourOfDay = 0;
        int t = s.indexOf('T');
        if (t >= 0) {
            datePart = s.substring(0, t);
            String timePart = s.substring(t + 1);
            String[] hms = timePart.split(":");
            if (hms.length >= 2) {
                hourOfDay = Integer.parseInt(hms[0]) + Integer.parseInt(hms[1]) / 60.0;
            }
        }
        String[] ymd = datePart.split("-");
        return new int[]{
                Integer.parseInt(ymd[0]),
                Integer.parseInt(ymd[1]),
                Integer.parseInt(ymd[2]),
                (int) hourOfDay,
                (int) Math.round((hourOfDay - (int) hourOfDay) * 60)
        };
    }
}
