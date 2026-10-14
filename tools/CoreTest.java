import com.xtlure.app.core.CoordinateTransform;
import com.xtlure.app.core.SunMoon;

import java.util.Calendar;
import java.util.TimeZone;

/** 纯 Java 自检：坐标转换 + 日月计算。不依赖 Android。 */
public class CoreTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        System.out.println("=== 坐标转换 ===");

        // 邢台市襄都区 达活泉公园 附近，GPS 原始坐标约 37.0750, 114.4900
        double lat = 37.0750, lon = 114.4900;
        double[] gcj = CoordinateTransform.wgs84ToGcj02(lat, lon);
        double dGcj = CoordinateTransform.distanceMeters(lat, lon, gcj[0], gcj[1]);
        System.out.printf("  WGS84  %.6f, %.6f%n", lat, lon);
        System.out.printf("  GCJ02  %.6f, %.6f   (偏移 %.0f m)%n", gcj[0], gcj[1], dGcj);
        check("WGS84->GCJ02 偏移在合理区间(200~900m)", dGcj > 200 && dGcj < 900,
                String.format("%.0f m", dGcj));

        double[] back = CoordinateTransform.gcj02ToWgs84(gcj[0], gcj[1]);
        double err = CoordinateTransform.distanceMeters(lat, lon, back[0], back[1]);
        check("GCJ02->WGS84 往返误差 < 5m", err < 5, String.format("%.2f m", err));

        double[] bd = CoordinateTransform.wgs84ToBd09(lat, lon);
        double[] bdBack = CoordinateTransform.bd09ToWgs84(bd[0], bd[1]);
        double errBd = CoordinateTransform.distanceMeters(lat, lon, bdBack[0], bdBack[1]);
        System.out.printf("  BD09   %.6f, %.6f%n", bd[0], bd[1]);
        check("BD09 往返误差 < 5m", errBd < 5, String.format("%.2f m", errBd));

        // 境外不偏移
        double[] nf = CoordinateTransform.wgs84ToGcj02(35.6762, 139.6503); // 东京
        check("境外坐标不做偏移", nf[0] == 35.6762 && nf[1] == 139.6503, "");

        // 已知参考点交叉验证：天安门 WGS84(39.90750, 116.39123) 约 -> GCJ02(39.90870, 116.39745)
        double[] tam = CoordinateTransform.wgs84ToGcj02(39.90750, 116.39123);
        boolean tamOk = Math.abs(tam[0] - 39.90869) < 0.002 && Math.abs(tam[1] - 116.39747) < 0.002;
        check("天安门参考点交叉验证", tamOk, String.format("%.5f, %.5f", tam[0], tam[1]));

        System.out.println("\n=== 距离 / 格式 ===");
        double d = CoordinateTransform.distanceMeters(37.0750, 114.4900, 37.0750, 114.5000);
        check("同纬度 0.01 经度 ≈ 886m", Math.abs(d - 886) < 15, String.format("%.0f m", d));
        System.out.println("  formatDistance(886) = " + CoordinateTransform.formatDistance(886));
        System.out.println("  formatDistance(4321) = " + CoordinateTransform.formatDistance(4321));
        System.out.println("  DMS lat = " + CoordinateTransform.toDms(37.07504, true));
        System.out.println("  DMS lon = " + CoordinateTransform.toDms(114.49004, false));

        System.out.println("\n=== 日出日落（邢台 2026-06-21，夏至）===");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        double[] ss = SunMoon.sunriseSunset(2026, 6, 21, 37.0750, 114.4900, 8);
        System.out.println("  日出 " + SunMoon.hourToTime(ss[0]) + "  日落 " + SunMoon.hourToTime(ss[1]));
        // 邢台夏至实际约 04:57 / 19:44
        check("夏至日出 ≈ 04:5x", ss[0] > 4.7 && ss[0] < 5.2, SunMoon.hourToTime(ss[0]));
        check("夏至日落 ≈ 19:4x", ss[1] > 19.4 && ss[1] < 19.9, SunMoon.hourToTime(ss[1]));
        check("夏至昼长 > 14h", (ss[1] - ss[0]) > 14.0,
                String.format("%.2f h", ss[1] - ss[0]));

        System.out.println("\n=== 日出日落（邢台 2026-12-21，冬至）===");
        double[] ss2 = SunMoon.sunriseSunset(2026, 12, 21, 37.0750, 114.4900, 8);
        System.out.println("  日出 " + SunMoon.hourToTime(ss2[0]) + "  日落 " + SunMoon.hourToTime(ss2[1]));
        // 邢台冬至实际约 07:28 / 17:12
        check("冬至日出 ≈ 07:2x", ss2[0] > 7.2 && ss2[0] < 7.7, SunMoon.hourToTime(ss2[0]));
        check("冬至日落 ≈ 17:1x", ss2[1] > 16.9 && ss2[1] < 17.4, SunMoon.hourToTime(ss2[1]));
        check("冬至昼长 < 10h", (ss2[1] - ss2[0]) < 10.0,
                String.format("%.2f h", ss2[1] - ss2[0]));

        System.out.println("\n=== 太阳高度角（邢台 2026-06-21 12:00）===");
        double alt = SunMoon.solarAltitude(2026, 6, 21, 12.0, 37.0750, 114.4900, 8);
        // 夏至正午高度角 = 90 - 37.075 + 23.44 ≈ 76.4
        System.out.printf("  正午高度角 %.2f°%n", alt);
        check("夏至正午高度角 ≈ 76°", Math.abs(alt - 76.4) < 1.5, String.format("%.2f", alt));
        double altNight = SunMoon.solarAltitude(2026, 6, 21, 0.0, 37.0750, 114.4900, 8);
        System.out.printf("  午夜高度角 %.2f°%n", altNight);
        check("午夜高度角 < 0（太阳在地平线下）", altNight < 0, String.format("%.2f", altNight));

        System.out.println("\n=== 太阳高度角（邢台 2026-12-21 12:00 冬至）===");
        double altW = SunMoon.solarAltitude(2026, 12, 21, 12.0, 37.0750, 114.4900, 8);
        // 冬至正午高度角 = 90 - 37.075 - 23.44 ≈ 29.5
        System.out.printf("  正午高度角 %.2f°%n", altW);
        check("冬至正午高度角 ≈ 29.5°", Math.abs(altW - 29.5) < 1.5, String.format("%.2f", altW));

        System.out.println("\n=== 太阳方位角（邢台 2026-06-21）===");
        double azNoon = SunMoon.solarAzimuth(2026, 6, 21, 12.4, 37.0750, 114.4900, 8);
        System.out.printf("  正午前后方位角 %.1f°（应接近正南 180°）%n", azNoon);
        check("正午方位角接近正南", Math.abs(azNoon - 180.0) < 12.0, String.format("%.1f", azNoon));
        double azMorning = SunMoon.solarAzimuth(2026, 6, 21, 6.0, 37.0750, 114.4900, 8);
        System.out.printf("  06:00 方位角 %.1f°（应在东北 60-80°）%n", azMorning);
        check("清晨方位角在东北", azMorning > 55 && azMorning < 95, String.format("%.1f", azMorning));
        double azEvening = SunMoon.solarAzimuth(2026, 6, 21, 19.0, 37.0750, 114.4900, 8);
        System.out.printf("  19:00 方位角 %.1f°（应在西北 280-300°）%n", azEvening);
        check("傍晚方位角在西北", azEvening > 270 && azEvening < 305, String.format("%.1f", azEvening));

        System.out.println("\n=== 日出日落精度收紧（对比公开天文数据）===");
        // 邢台 2026-06-21 实际约 04:59 / 19:44
        check("夏至日出在 04:57-05:03", ss[0] > (4 + 57 / 60.0) && ss[0] < (5 + 3 / 60.0), SunMoon.hourToTime(ss[0]));
        check("夏至日落在 19:41-19:47", ss[1] > (19 + 41 / 60.0) && ss[1] < (19 + 47 / 60.0), SunMoon.hourToTime(ss[1]));
        // 邢台 2026-12-21 实际约 07:28 / 17:11
        check("冬至日出在 07:25-07:32", ss2[0] > (7 + 25 / 60.0) && ss2[0] < (7 + 32 / 60.0), SunMoon.hourToTime(ss2[0]));
        check("冬至日落在 17:08-17:15", ss2[1] > (17 + 8 / 60.0) && ss2[1] < (17 + 15 / 60.0), SunMoon.hourToTime(ss2[1]));
        System.out.println("  太阳正午（夏至）= " + SunMoon.hourToTime(ss[2]));

        System.out.println("\n=== 月相 ===");
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.set(2026, Calendar.JANUARY, 3, 12, 0, 0); // 2026-01-03 满月附近
        double age = SunMoon.moonAge(c);
        System.out.printf("  2026-01-03 月龄 %.2f 天  照明 %.0f%%  %s %s%n",
                age, SunMoon.moonIllumination(age) * 100,
                SunMoon.moonPhaseEmoji(age), SunMoon.moonPhaseName(age));
        // 2026-01-03 是满月
        check("2026-01-03 判定为满月附近", age > 12 && age < 18, String.format("age=%.2f", age));

        c.set(2026, Calendar.JANUARY, 19, 12, 0, 0); // 2026-01-19 新月附近
        double age2 = SunMoon.moonAge(c);
        System.out.printf("  2026-01-19 月龄 %.2f 天  照明 %.0f%%  %s %s%n",
                age2, SunMoon.moonIllumination(age2) * 100,
                SunMoon.moonPhaseEmoji(age2), SunMoon.moonPhaseName(age2));
        boolean newish = age2 < 2.5 || age2 > 27;
        check("2026-01-19 判定为新月附近", newish, String.format("age=%.2f", age2));

        System.out.println("\n=== 评分 ===");
        check("新月评分高", SunMoon.fishActivityScore(0.5) >= 80, "");
        check("满月评分高", SunMoon.fishActivityScore(14.76) >= 80, "");
        check("弦月评分低", SunMoon.fishActivityScore(7.4) <= 55, "");

        System.out.println("\n================================");
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
