import com.xtlure.app.core.CoordinateTransform;
import com.xtlure.app.core.SunMoon;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 契约测试（Java 侧）。
 *
 * 所有期望值来自 testdata/vectors.json —— 与 Swift 侧读的是同一份文件。
 * 这个类只负责"把向量喂给实现、断言结果"，不自己写期望值。
 *
 * 与 CoreTest 的分工：
 *   CoreTest   记录实现"现在算出来是多少"（含打印观测值，便于人工核对）
 *   VectorTest 断言"必须落在契约规定的范围内"（跨平台一致的判据）
 * 两者都要跑，VectorTest 是移植时的验收标准。
 */
public class VectorTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (ok) {
            pass++;
        } else {
            fail++;
        }
    }

    /** 区间断言（含端点） */
    static void inRange(String name, double value, double lo, double hi, String unit) {
        check(name, value >= lo && value <= hi,
                String.format("%.4f%s  期望 [%.3f, %.3f]", value, unit, lo, hi));
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        VectorReader v;
        try {
            v = VectorReader.load();
        } catch (Exception e) {
            System.out.println("  FAIL  载入契约文件");
            System.out.println("        " + e.getMessage());
            System.out.println("\nPASS=0  FAIL=1");
            System.exit(1);
            return;
        }

        System.out.println("=== 契约: coordinateTransform ===");
        List<Map<String, Object>> coords = v.list("coordinateTransform");
        check("向量条数符合预期(5)", coords.size() == 5, coords.size() + " 条");
        for (Map<String, Object> c : coords) {
            String id = VectorReader.id(c);
            double[] wgs = VectorReader.pair(c, "wgs84");
            if (wgs == null) {
                check(id, false, "缺少 wgs84");
                continue;
            }

            if (VectorReader.has(c, "offsetRange")) {
                double[] rg = VectorReader.range(c, "offsetRange");
                double[] gcj = CoordinateTransform.wgs84ToGcj02(wgs[0], wgs[1]);
                double off = CoordinateTransform.distanceMeters(wgs[0], wgs[1], gcj[0], gcj[1]);
                inRange(id, off, rg[0], rg[1], "m");
            } else if (VectorReader.has(c, "roundtripMaxMeters")) {
                double max = VectorReader.num(c, "roundtripMaxMeters", 5);
                // 用两个坐标系各测一遍：GCJ-02 与 BD-09
                double[] gcj = CoordinateTransform.wgs84ToGcj02(wgs[0], wgs[1]);
                double[] gBack = CoordinateTransform.gcj02ToWgs84(gcj[0], gcj[1]);
                double eg = CoordinateTransform.distanceMeters(wgs[0], wgs[1], gBack[0], gBack[1]);
                check(id + "(GCJ-02)", eg < max, String.format("%.2f m < %.1f", eg, max));

                double[] bd = CoordinateTransform.wgs84ToBd09(wgs[0], wgs[1]);
                double[] bBack = CoordinateTransform.bd09ToWgs84(bd[0], bd[1]);
                double eb = CoordinateTransform.distanceMeters(wgs[0], wgs[1], bBack[0], bBack[1]);
                check(id + "(BD-09)", eb < max, String.format("%.2f m < %.1f", eb, max));
            } else if (VectorReader.bool(c, "expectUnchanged", false)) {
                double[] out = CoordinateTransform.wgs84ToGcj02(wgs[0], wgs[1]);
                check(id, out[0] == wgs[0] && out[1] == wgs[1],
                        String.format("%.4f, %.4f", out[0], out[1]));
            } else if (VectorReader.has(c, "gcj02Expect")) {
                double[] exp = VectorReader.pair(c, "gcj02Expect");
                double tol = VectorReader.num(c, "tol", 0.002);
                double[] got = CoordinateTransform.wgs84ToGcj02(wgs[0], wgs[1]);
                boolean ok = Math.abs(got[0] - exp[0]) < tol && Math.abs(got[1] - exp[1]) < tol;
                check(id, ok, String.format("%.5f, %.5f  期望 %.5f, %.5f ±%.4f",
                        got[0], got[1], exp[0], exp[1], tol));
            }
        }

        System.out.println("\n=== 契约: distance ===");
        for (Map<String, Object> c : v.list("distance")) {
            String id = VectorReader.id(c);
            double[] from = VectorReader.pair(c, "from");
            double[] to = VectorReader.pair(c, "to");
            double[] rg = VectorReader.range(c, "range");
            if (from == null || to == null || rg == null) {
                check(id, false, "向量字段不全");
                continue;
            }
            double d = CoordinateTransform.distanceMeters(from[0], from[1], to[0], to[1]);
            inRange(id, d, rg[0], rg[1], "m");
        }

        System.out.println("\n=== 契约: format ===");
        for (Map<String, Object> c : v.list("format")) {
            String id = VectorReader.id(c);
            double m = VectorReader.num(c, "meters", 0);
            String exp = VectorReader.str(c, "expectStr", "");
            String got = CoordinateTransform.formatDistance(m);
            check(id, exp.equals(got), "'" + got + "'  期望 '" + exp + "'");
        }

        System.out.println("\n=== 契约: sunMoon.sunriseSunset ===");
        Map<String, Object> sm = v.section("sunMoon");
        double lat = VectorReader.num(sm, "lat", 37.075);
        double lon = VectorReader.num(sm, "lon", 114.49);
        int tz = (int) VectorReader.num(sm, "tzOffsetHours", 8);

        for (Map<String, Object> c : v.list("sunMoon", "sunriseSunset")) {
            String id = VectorReader.id(c);
            int[] d = VectorReader.parseDateTime(VectorReader.str(c, "date", "2026-06-21"));
            double[] ss = SunMoon.sunriseSunset(d[0], d[1], d[2], lat, lon, tz);
            double len = ss[1] - ss[0];

            double[] sr = VectorReader.range(c, "sunriseRange");
            double[] su = VectorReader.range(c, "sunsetRange");
            double[] dl = VectorReader.range(c, "dayLengthRange");
            if (sr != null) {
                inRange(id + " 日出", ss[0], sr[0], sr[1], "");
            }
            if (su != null) {
                inRange(id + " 日落", ss[1], su[0], su[1], "");
            }
            if (dl != null) {
                inRange(id + " 昼长", len, dl[0], dl[1], "h");
            }
        }

        System.out.println("\n=== 契约: sunMoon.solarAltitude ===");
        for (Map<String, Object> c : v.list("sunMoon", "solarAltitude")) {
            String id = VectorReader.id(c);
            int[] d = VectorReader.parseDateTime(VectorReader.str(c, "date", "2026-06-21"));
            double hour = VectorReader.num(c, "hour", 12);
            double alt = SunMoon.solarAltitude(d[0], d[1], d[2], hour, lat, lon, tz);
            if (VectorReader.has(c, "expect")) {
                double exp = VectorReader.num(c, "expect", 0);
                double tol = VectorReader.num(c, "tol", 1.5);
                check(id, Math.abs(alt - exp) <= tol,
                        String.format("%.2f°  期望 %.1f ±%.1f", alt, exp, tol));
            } else if (VectorReader.has(c, "max")) {
                double max = VectorReader.num(c, "max", 0);
                check(id, alt < max, String.format("%.2f° < %.1f", alt, max));
            }
        }

        System.out.println("\n=== 契约: sunMoon.solarAzimuth ===");
        for (Map<String, Object> c : v.list("sunMoon", "solarAzimuth")) {
            String id = VectorReader.id(c);
            int[] d = VectorReader.parseDateTime(VectorReader.str(c, "date", "2026-06-21"));
            double hour = VectorReader.num(c, "hour", 12);
            double az = SunMoon.solarAzimuth(d[0], d[1], d[2], hour, lat, lon, tz);
            if (VectorReader.has(c, "expect")) {
                double exp = VectorReader.num(c, "expect", 0);
                double tol = VectorReader.num(c, "tol", 12);
                check(id, Math.abs(az - exp) <= tol,
                        String.format("%.1f°  期望 %.1f ±%.1f", az, exp, tol));
            } else if (VectorReader.has(c, "range")) {
                double[] rg = VectorReader.range(c, "range");
                inRange(id, az, rg[0], rg[1], "°");
            }
        }

        System.out.println("\n=== 契约: sunMoon.moonAge ===");
        for (Map<String, Object> c : v.list("sunMoon", "moonAge")) {
            String id = VectorReader.id(c);
            int[] d = VectorReader.parseDateTime(VectorReader.str(c, "datetime", "2026-01-03T12:00:00"));
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            cal.set(d[0], d[1] - 1, d[2], d[3], d[4], 0);
            cal.set(Calendar.MILLISECOND, 0);
            double age = SunMoon.moonAge(cal);

            if (VectorReader.has(c, "range")) {
                double[] rg = VectorReader.range(c, "range");
                inRange(id, age, rg[0], rg[1], "天");
            } else if (VectorReader.has(c, "eitherRange")) {
                double[][] rs = VectorReader.ranges(c, "eitherRange");
                boolean ok = false;
                StringBuilder sb = new StringBuilder(String.format("age=%.2f 命中 ", age));
                for (double[] r : rs) {
                    if (age >= r[0] && age <= r[1]) {
                        ok = true;
                        sb.append(String.format("[%.1f,%.1f] ", r[0], r[1]));
                    }
                }
                if (!ok) {
                    sb.append("无区间");
                }
                check(id, ok, sb.toString());
            }
        }

        System.out.println("\n=== 契约: sunMoon.fishActivityScore ===");
        for (Map<String, Object> c : v.list("sunMoon", "fishActivityScore")) {
            String id = VectorReader.id(c);
            double moonAge = VectorReader.num(c, "moonAge", 0);
            int score = SunMoon.fishActivityScore(moonAge);
            if (VectorReader.has(c, "min")) {
                double min = VectorReader.num(c, "min", 0);
                check(id, score >= min, score + " 分  >= " + (int) min);
            } else if (VectorReader.has(c, "max")) {
                double max = VectorReader.num(c, "max", 0);
                check(id, score <= max, score + " 分  <= " + (int) max);
            }
        }

        System.out.println("\n=== 契约: theme 对比度下限 ===");
        Map<String, Object> theme = v.section("theme");
        if (theme != null) {
            for (String scheme : new String[]{"dark", "light"}) {
                Map<String, Object> t = com.xtlure.app.util.Json.child(theme, scheme);
                if (t == null) {
                    continue;
                }
                double minCard = VectorReader.num(t, "minCardVsBg", 1.05);
                double cardVsBg = contrast(parseColor(VectorReader.str(t, "card", "#FFFFFF")),
                        parseColor(VectorReader.str(t, "bg", "#000000")));
                check(scheme + " 卡片与底色有区分", cardVsBg >= minCard,
                        String.format("%.3f >= %.2f", cardVsBg, minCard));

                double minText = VectorReader.num(t, "minTextOnCard", 4.5);
                double textVsCard = contrast(parseColor(VectorReader.str(t, "text", "#FFFFFF")),
                        parseColor(VectorReader.str(t, "card", "#000000")));
                check(scheme + " 正文对比度达到 AA", textVsCard >= minText,
                        String.format("%.2f >= %.1f", textVsCard, minText));

                double minAccent = VectorReader.num(t, "minAccentText", 3.0);
                double accentVsCard = contrast(parseColor(VectorReader.str(t, "accent", "#00FF00")),
                        parseColor(VectorReader.str(t, "card", "#000000")));
                check(scheme + " 主色文字可辨认", accentVsCard >= minAccent,
                        String.format("%.2f >= %.1f", accentVsCard, minAccent));
            }
        }

        // ---------------------------------------------------------------- 补充契约
        //
        // 地图投影部分原来**两侧都没测**（契约文件里写着期望值却无人校验）。
        // 详见 VectorTestMap.java 的说明。
        // 与 Swift 侧的 ios/Sources/VectorTest/ContractExtra.swift 对应。
        VectorTestMap.Result mapResult = VectorTestMap.run(v);
        pass += mapResult.pass;
        fail += mapResult.fail;

        // 禁渔期判定：判错会让用户在禁渔期下竿，是有合规后果的功能。
        // 而且含跨年分支（8~12 月要算到次年 5 月 16 日），最容易漏。
        VectorTestMap.Result seasonResult = VectorTestMap.runClosedSeason(v, v);
        pass += seasonResult.pass;
        fail += seasonResult.fail;

        System.out.println("\n================================");
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 对比度

    private static int[] parseColor(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 8) {
            s = s.substring(2);     // 去掉 alpha
        }
        return new int[]{
                Integer.parseInt(s.substring(0, 2), 16),
                Integer.parseInt(s.substring(2, 4), 16),
                Integer.parseInt(s.substring(4, 6), 16)
        };
    }

    private static double relLuminance(int[] rgb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = rgb[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(int[] a, int[] b) {
        double la = relLuminance(a), lb = relLuminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }
}
