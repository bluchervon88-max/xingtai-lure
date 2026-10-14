package com.xtlure.app.core;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 天文计算：日出、日落、太阳高度角、月相、月龄。
 *
 * 路亚作钓强依赖光线：日出前后与日落前两小时是翘嘴/鲈鱼的窗口期；
 * 月相与月龄影响夜间作钓与鱼口。本类不依赖网络，纯本地计算。
 *
 * 算法：Meeus《Astronomical Algorithms》简化太阳位置公式 + NOAA 日出日落公式。
 * 精度：日出日落 ±2 分钟量级，太阳高度角 ±0.5° 量级。
 */
public final class SunMoon {

    private SunMoon() {
    }

    // ================================================================ 儒略日

    /** 公历日期 -> 儒略日（含小数） */
    public static double julianDay(int year, int month, int day, double hourUtc) {
        int y = year;
        int m = month;
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        int a = y / 100;
        int b = 2 - a + (a / 4);
        double jd = Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1))
                + day + b - 1524.5;
        return jd + hourUtc / 24.0;
    }

    /** 从 J2000.0 起算的天数 */
    private static double daysSinceJ2000(double jd) {
        return jd - 2451545.0;
    }

    /**
     * ΔT 近似（TT - UT，秒）。Espenak & Meeus 多项式，2005-2050 段。
     */
    public static double deltaTSeconds(int year) {
        double y = year;
        if (year >= 2005 && year <= 2050) {
            double t = y - 2000.0;
            return 62.92 + 0.32217 * t + 0.005589 * t * t;
        }
        // 粗略外推
        double u = (y - 1820.0) / 100.0;
        return -20 + 32 * u * u;
    }

    // ================================================================ 太阳位置

    /** 太阳位置结果：黄经、赤经、赤纬、均时差（分钟） */
    public static final class SolarPos {
        public double eclipticLon;   // 视黄经（度）
        public double ra;            // 赤经（度）
        public double dec;           // 赤纬（度）
        public double eqOfTimeMin;   // 均时差（分钟）= 真太阳时 - 平太阳时
    }

    /**
     * 计算给定儒略日的太阳位置。
     * @param jd 儒略日（UT 时标）
     */
    public static SolarPos solarPosition(double jd) {
        double T = daysSinceJ2000(jd) / 36525.0;

        // 太阳几何平黄经
        double L0 = 280.46646 + 36000.76983 * T + 0.0003032 * T * T;
        L0 = norm360(L0);

        // 平近点角
        double M = 357.52911 + 35999.05029 * T - 0.0001537 * T * T;
        double Mr = Math.toRadians(M);

        // 中心差
        double C = (1.914602 - 0.004817 * T - 0.000014 * T * T) * Math.sin(Mr)
                + (0.019993 - 0.000101 * T) * Math.sin(2 * Mr)
                + 0.000289 * Math.sin(3 * Mr);

        // 真黄经
        double trueLon = L0 + C;

        // 黄赤交角
        double eps = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0
                - (46.8150 * T + 0.00059 * T * T - 0.001813 * T * T * T) / 3600.0;
        double epsR = Math.toRadians(eps);

        double lonR = Math.toRadians(trueLon);
        double ra = Math.toDegrees(Math.atan2(Math.cos(epsR) * Math.sin(lonR), Math.cos(lonR)));
        ra = norm360(ra);
        double dec = Math.toDegrees(Math.asin(Math.sin(epsR) * Math.sin(lonR)));

        SolarPos p = new SolarPos();
        p.eclipticLon = trueLon;
        p.ra = ra;
        p.dec = dec;

        // 均时差
        double y = Math.tan(epsR / 2);
        y = y * y;
        double e = 0.016708634 - 0.000042037 * T - 0.0000001267 * T * T;
        double eqTime = y * Math.sin(2 * Math.toRadians(L0))
                - 2 * e * Math.sin(Mr)
                + 4 * e * y * Math.sin(Mr) * Math.cos(2 * Math.toRadians(L0))
                - 0.5 * y * y * Math.sin(4 * Math.toRadians(L0))
                - 1.25 * e * e * Math.sin(2 * Mr);
        p.eqOfTimeMin = Math.toDegrees(eqTime) * 4.0;

        return p;
    }

    /** 格林尼治平恒星时（度） */
    private static double gmstDegrees(double jd) {
        double d = daysSinceJ2000(jd);
        double gmst = 280.46061837 + 360.98564736629 * d
                + 0.000387933 * Math.pow(d / 36525.0, 2)
                - Math.pow(d / 36525.0, 3) / 38710000.0;
        return norm360(gmst);
    }

    /**
     * 太阳高度角（度）。负值表示太阳在地平线以下。
     *
     * @param year,month,day 当地日期
     * @param hourLocal 当地时钟时间（小时的小数，如 6.5 表示 06:30）
     * @param lat,lon 经纬度（WGS-84，东经/北纬为正）
     * @param tz 时区偏移（中国 = 8）
     */
    public static double solarAltitude(int year, int month, int day, double hourLocal,
                                       double lat, double lon, double tz) {
        double hourUtc = hourLocal - tz;
        double jdUtc = julianDay(year, month, day, hourUtc);
        // 用 TT 计算太阳位置
        double jdTT = jdUtc + deltaTSeconds(year) / 86400.0;

        SolarPos p = solarPosition(jdTT);

        // 当地平恒星时 = GMST + 东经
        double lst = norm360(gmstDegrees(jdUtc) + lon);
        double H = norm360(lst - p.ra);
        if (H > 180) {
            H -= 360; // 归到 -180..180
        }

        double latR = Math.toRadians(lat);
        double decR = Math.toRadians(p.dec);
        double HR = Math.toRadians(H);

        double sinAlt = Math.sin(latR) * Math.sin(decR)
                + Math.cos(latR) * Math.cos(decR) * Math.cos(HR);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sinAlt))));
    }

    /** 太阳方位角（度，0=正北，顺时针） */
    public static double solarAzimuth(int year, int month, int day, double hourLocal,
                                      double lat, double lon, double tz) {
        double hourUtc = hourLocal - tz;
        double jdUtc = julianDay(year, month, day, hourUtc);
        double jdTT = jdUtc + deltaTSeconds(year) / 86400.0;
        SolarPos p = solarPosition(jdTT);
        double lst = norm360(gmstDegrees(jdUtc) + lon);
        double H = norm360(lst - p.ra);
        if (H > 180) {
            H -= 360;
        }
        double latR = Math.toRadians(lat);
        double decR = Math.toRadians(p.dec);
        double HR = Math.toRadians(H);
        double az = Math.toDegrees(Math.atan2(
                Math.sin(HR),
                Math.cos(HR) * Math.sin(latR) - Math.tan(decR) * Math.cos(latR)));
        return norm360(az + 180);
    }

    // ================================================================ 日出日落

    /**
     * 日出与日落时刻（当地时钟时间，小时的小数）。
     * 采用 NOAA 迭代法。极昼/极夜返回 NaN。
     *
     * @return double[]{日出, 日落, 太阳正午}
     */
    public static double[] sunriseSunset(int year, int month, int day,
                                         double lat, double lon, double tz) {
        // 以当地正午为基准的儒略日
        double jdNoon = julianDay(year, month, day, 12.0 - tz);
        SolarPos p = solarPosition(jdNoon + deltaTSeconds(year) / 86400.0);

        double latR = Math.toRadians(lat);
        double decR = Math.toRadians(p.dec);

        // 天顶角 90°50'，含大气折射与日面半径
        double zenith = Math.toRadians(90.0 + 50.0 / 60.0);
        double cosH = (Math.cos(zenith) - Math.sin(latR) * Math.sin(decR))
                / (Math.cos(latR) * Math.cos(decR));

        if (cosH > 1.0) {
            return new double[]{Double.NaN, Double.NaN, 12.0};  // 极夜
        }
        if (cosH < -1.0) {
            return new double[]{Double.NaN, Double.NaN, 12.0};  // 极昼
        }

        double H = Math.toDegrees(Math.acos(cosH)); // 度

        // 太阳正午（当地时间）= 12 + 时区*15 - 经度 - 均时差  ... 换算成小时
        double solarNoon = 12.0 + (tz * 15.0 - lon - p.eqOfTimeMin / 4.0) / 15.0;

        double halfDay = H / 15.0;
        double sunrise = solarNoon - halfDay;
        double sunset = solarNoon + halfDay;

        return new double[]{normHour(sunrise), normHour(sunset), normHour(solarNoon)};
    }

    // ================================================================ 月相

    /** 已知新月参考时刻：2000-01-06 18:14 UTC */
    private static final double KNOWN_NEW_MOON_JD = 2451550.1;
    private static final double SYNODIC_MONTH = 29.530588853;

    /** 月龄（0 ~ 29.53 天） */
    public static double moonAge(Calendar cal) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(cal.getTimeInMillis());
        double jd = julianDay(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1,
                utc.get(Calendar.DAY_OF_MONTH),
                utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0);
        double age = (jd - KNOWN_NEW_MOON_JD) % SYNODIC_MONTH;
        if (age < 0) {
            age += SYNODIC_MONTH;
        }
        return age;
    }

    public static double moonAgeToday() {
        return moonAge(Calendar.getInstance());
    }

    /** 照明比例 0 ~ 1 */
    public static double moonIllumination(double ageDays) {
        return (1 - Math.cos(2 * Math.PI * ageDays / SYNODIC_MONTH)) / 2.0;
    }

    /** 月相名称（中文） */
    public static String moonPhaseName(double ageDays) {
        double p = ageDays / SYNODIC_MONTH;
        if (p < 0.0335 || p >= 0.9665) return "新月（朔）";
        if (p < 0.2165) return "娥眉月";
        if (p < 0.2835) return "上弦月";
        if (p < 0.4665) return "盈凸月";
        if (p < 0.5335) return "满月（望）";
        if (p < 0.7165) return "亏凸月";
        if (p < 0.7835) return "下弦月";
        return "残月";
    }

    /** 月相 emoji */
    public static String moonPhaseEmoji(double ageDays) {
        double p = ageDays / SYNODIC_MONTH;
        if (p < 0.0335 || p >= 0.9665) return "🌑";
        if (p < 0.2165) return "🌒";
        if (p < 0.2835) return "🌓";
        if (p < 0.4665) return "🌔";
        if (p < 0.5335) return "🌕";
        if (p < 0.7165) return "🌖";
        if (p < 0.7835) return "🌗";
        return "🌘";
    }

    /**
     * 今日作钓启发式评分（0-100）。
     * 依据：新月/满月前后通常鱼口更活跃，弦月期间相对平淡。
     * 这是经验性参考值，不是科学预测，UI 中已标注"参考"。
     */
    public static int fishActivityScore(double moonAgeDays) {
        double d = Math.min(moonAgeDays, SYNODIC_MONTH - moonAgeDays);
        double dFull = Math.abs(moonAgeDays - SYNODIC_MONTH / 2.0);
        double near = Math.min(d, dFull);
        if (near <= 2) return 85;
        if (near <= 4) return 70;
        if (near <= 7) return 55;
        return 45;
    }

    // ================================================================ 工具

    /** 一年中的第几天 */
    public static int dayOfYear(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, day);
        return c.get(Calendar.DAY_OF_YEAR);
    }

    /** 小时的小数 -> "HH:mm" */
    public static String hourToTime(double hour) {
        if (Double.isNaN(hour)) {
            return "--:--";
        }
        int h = (int) Math.floor(hour);
        int m = (int) Math.round((hour - h) * 60);
        if (m == 60) {
            m = 0;
            h += 1;
        }
        h %= 24;
        return String.format(Locale.CHINA, "%02d:%02d", h, m);
    }

    private static double norm360(double v) {
        double r = v % 360.0;
        if (r < 0) {
            r += 360.0;
        }
        return r;
    }

    private static double normHour(double h) {
        double r = h % 24.0;
        if (r < 0) {
            r += 24.0;
        }
        return r;
    }
}
