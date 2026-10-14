// ★ 本文件是生成物，请勿直接修改。
//
// 单一来源: ios/Sources/XtLureCore/SunMoon.swift
// 同步方式: node ios/sync-sources.js
//
// 为什么要有这份副本：Xcode 工程按固定相对路径找文件，而逻辑层的单一来源
// 放在 XtLureCore 是为了能在 Windows 上用 swiftc 直接编译测试。
// 直接改这份副本会在下次同步时被覆盖 —— 请改单一来源。
import Foundation

/// 天文计算：日出、日落、太阳高度角、月相、月龄。
///
/// 路亚作钓强依赖光线：日出前后与日落前两小时是翘嘴/鲈鱼的窗口期；
/// 月相与月龄影响夜间作钓与鱼口。本类不依赖网络，纯本地计算。
///
/// 算法：Meeus《Astronomical Algorithms》简化太阳位置公式 + NOAA 日出日落公式。
/// 精度：日出日落 ±2 分钟量级，太阳高度角 ±0.5° 量级。
///
/// ★ 这是 Android 版 `SunMoon.java` 的**忠实移植**，也是整个移植中技术风险最高的一块：
///   契约里夏至/冬至日出日落的窗口只有几十秒宽（例如 [4.950, 5.050]，而 Java 实测 5.0413），
///   浮点实现的任何细微差异都会掉出窗口。这里逐行对照移植，并把差异点写在注释里。
///
/// ★ Java -> Swift 的语义差异（已逐处处理）：
///   1. `Math.round(x)` = `floor(x + 0.5)`，负数向正无穷取整；
///      Swift `.rounded()` 默认是 half-away-from-zero。凡涉及 Math.round 处都显式写成
///      `.rounded(.toNearestOrAwayFromZero)` 或保留 Java 语义的写法。
///   2. `Math.floor(x)` = Swift `.rounded(.down)`。
///   3. 字符串格式化：Java `%c` 传 char，Swift 没有等价写法，改用 `%@` 传 String。
///   4. 整数除法两者都是向零截断，一致。
///   5. 浮点取模 `%` 两者都保留被除数符号，一致。
public enum SunMoon {

    // MARK: - 儒略日

    /// 公历日期 -> 儒略日（含小数）
    public static func julianDay(_ year: Int, _ month: Int, _ day: Int,
                                 _ hourUtc: Double) -> Double {
        var y = year
        var m = month
        if m <= 2 {
            y -= 1
            m += 12
        }
        let a = y / 100
        let b = 2 - a + (a / 4)
        // Java 的 Math.floor 对正数就是取整；这里保持 floor 语义
        let jd = (365.25 * Double(y + 4716)).rounded(.down)
            + (30.6001 * Double(m + 1)).rounded(.down)
            + Double(day) + Double(b) - 1524.5
        return jd + hourUtc / 24.0
    }

    /// 从 J2000.0 起算的天数
    private static func daysSinceJ2000(_ jd: Double) -> Double {
        return jd - 2451545.0
    }

    /// ΔT 近似（TT - UT，秒）。Espenak & Meeus 多项式，2005-2050 段。
    public static func deltaTSeconds(_ year: Int) -> Double {
        let y = Double(year)
        if year >= 2005 && year <= 2050 {
            let t = y - 2000.0
            return 62.92 + 0.32217 * t + 0.005589 * t * t
        }
        let u = (y - 1820.0) / 100.0
        return -20 + 32 * u * u
    }

    // MARK: - 太阳位置

    /// 太阳位置结果：黄经、赤经、赤纬、均时差（分钟）
    public struct SolarPos {
        /// 视黄经（度）
        public var eclipticLon: Double = 0
        /// 赤经（度）
        public var ra: Double = 0
        /// 赤纬（度）
        public var dec: Double = 0
        /// 均时差（分钟）= 真太阳时 - 平太阳时
        public var eqOfTimeMin: Double = 0
    }

    /// 计算给定儒略日的太阳位置。
    /// - Parameter jd: 儒略日（UT 时标）
    public static func solarPosition(_ jd: Double) -> SolarPos {
        let T = daysSinceJ2000(jd) / 36525.0

        // 太阳几何平黄经
        var L0 = 280.46646 + 36000.76983 * T + 0.0003032 * T * T
        L0 = norm360(L0)

        // 平近点角
        let M = 357.52911 + 35999.05029 * T - 0.0001537 * T * T
        let Mr = M * Double.pi / 180.0

        // 中心差
        let C = (1.914602 - 0.004817 * T - 0.000014 * T * T) * sin(Mr)
            + (0.019993 - 0.000101 * T) * sin(2 * Mr)
            + 0.000289 * sin(3 * Mr)

        // 真黄经
        let trueLon = L0 + C

        // 黄赤交角
        let eps = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0
            - (46.8150 * T + 0.00059 * T * T - 0.001813 * T * T * T) / 3600.0
        let epsR = eps * Double.pi / 180.0

        let lonR = trueLon * Double.pi / 180.0
        var ra = atan2(cos(epsR) * sin(lonR), cos(lonR)) * 180.0 / Double.pi
        ra = norm360(ra)
        let dec = asin(sin(epsR) * sin(lonR)) * 180.0 / Double.pi

        var p = SolarPos()
        p.eclipticLon = trueLon
        p.ra = ra
        p.dec = dec

        // 均时差
        var y = tan(epsR / 2)
        y = y * y
        let e = 0.016708634 - 0.000042037 * T - 0.0000001267 * T * T
        let eqTime = y * sin(2 * L0 * Double.pi / 180.0)
            - 2 * e * sin(Mr)
            + 4 * e * y * sin(Mr) * cos(2 * L0 * Double.pi / 180.0)
            - 0.5 * y * y * sin(4 * L0 * Double.pi / 180.0)
            - 1.25 * e * e * sin(2 * Mr)
        p.eqOfTimeMin = eqTime * 180.0 / Double.pi * 4.0

        return p
    }

    /// 格林尼治平恒星时（度）
    private static func gmstDegrees(_ jd: Double) -> Double {
        let d = daysSinceJ2000(jd)
        let gmst = 280.46061837 + 360.98564736629 * d
            + 0.000387933 * pow(d / 36525.0, 2)
            - pow(d / 36525.0, 3) / 38710000.0
        return norm360(gmst)
    }

    // MARK: - 太阳高度角 / 方位角

    /// 太阳高度角（度）。负值表示太阳在地平线以下。
    /// - Parameters:
    ///   - hourLocal: 当地时钟时间（小时的小数，如 6.5 表示 06:30）
    ///   - tz: 时区偏移（中国 = 8）
    public static func solarAltitude(_ year: Int, _ month: Int, _ day: Int,
                                     _ hourLocal: Double,
                                     _ lat: Double, _ lon: Double, _ tz: Double) -> Double {
        let hourUtc = hourLocal - tz
        let jdUtc = julianDay(year, month, day, hourUtc)
        // 用 TT 计算太阳位置
        let jdTT = jdUtc + deltaTSeconds(year) / 86400.0

        let p = solarPosition(jdTT)

        // 当地平恒星时 = GMST + 东经
        let lst = norm360(gmstDegrees(jdUtc) + lon)
        var H = norm360(lst - p.ra)
        if H > 180 {
            H -= 360      // 归到 -180..180
        }

        let latR = lat * Double.pi / 180.0
        let decR = p.dec * Double.pi / 180.0
        let HR = H * Double.pi / 180.0

        let sinAlt = sin(latR) * sin(decR) + cos(latR) * cos(decR) * cos(HR)
        return asin(max(-1, min(1, sinAlt))) * 180.0 / Double.pi
    }

    /// 太阳方位角（度，0=正北，顺时针）
    public static func solarAzimuth(_ year: Int, _ month: Int, _ day: Int,
                                    _ hourLocal: Double,
                                    _ lat: Double, _ lon: Double, _ tz: Double) -> Double {
        let hourUtc = hourLocal - tz
        let jdUtc = julianDay(year, month, day, hourUtc)
        let jdTT = jdUtc + deltaTSeconds(year) / 86400.0
        let p = solarPosition(jdTT)
        let lst = norm360(gmstDegrees(jdUtc) + lon)
        var H = norm360(lst - p.ra)
        if H > 180 {
            H -= 360
        }
        let latR = lat * Double.pi / 180.0
        let decR = p.dec * Double.pi / 180.0
        let HR = H * Double.pi / 180.0
        let az = atan2(sin(HR),
                       cos(HR) * sin(latR) - tan(decR) * cos(latR)) * 180.0 / Double.pi
        return norm360(az + 180)
    }

    // MARK: - 日出日落

    /// 日出与日落时刻（当地时钟时间，小时的小数）。
    /// 采用 NOAA 迭代法。极昼/极夜返回 NaN。
    /// - Returns: (日出, 日落, 太阳正午)
    public static func sunriseSunset(_ year: Int, _ month: Int, _ day: Int,
                                     _ lat: Double, _ lon: Double,
                                     _ tz: Double) -> (sunrise: Double, sunset: Double, noon: Double) {
        // 以当地正午为基准的儒略日
        let jdNoon = julianDay(year, month, day, 12.0 - tz)
        let p = solarPosition(jdNoon + deltaTSeconds(year) / 86400.0)

        let latR = lat * Double.pi / 180.0
        let decR = p.dec * Double.pi / 180.0

        // 天顶角 90°50'，含大气折射与日面半径
        let zenith = (90.0 + 50.0 / 60.0) * Double.pi / 180.0
        let cosH = (cos(zenith) - sin(latR) * sin(decR)) / (cos(latR) * cos(decR))

        if cosH > 1.0 {
            return (Double.nan, Double.nan, 12.0)   // 极夜
        }
        if cosH < -1.0 {
            return (Double.nan, Double.nan, 12.0)   // 极昼
        }

        let H = acos(cosH) * 180.0 / Double.pi     // 度

        // 太阳正午（当地时间）= 12 + 时区*15 - 经度 - 均时差  ... 换算成小时
        let solarNoon = 12.0 + (tz * 15.0 - lon - p.eqOfTimeMin / 4.0) / 15.0

        let halfDay = H / 15.0
        let sunrise = solarNoon - halfDay
        let sunset = solarNoon + halfDay

        return (normHour(sunrise), normHour(sunset), normHour(solarNoon))
    }

    // MARK: - 月相

    /// 已知新月参考时刻：2000-01-06 18:14 UTC
    private static let KNOWN_NEW_MOON_JD = 2451550.1
    private static let SYNODIC_MONTH = 29.530588853

    /// 月龄（0 ~ 29.53 天）
    ///
    /// 与 Java 版一样接收 UTC 时刻的日历字段，避免时区处理引入差异。
    public static func moonAge(year: Int, month: Int, day: Int,
                               hour: Int, minute: Int) -> Double {
        let jd = julianDay(year, month, day, Double(hour) + Double(minute) / 60.0)
        var age = (jd - KNOWN_NEW_MOON_JD).truncatingRemainder(dividingBy: SYNODIC_MONTH)
        if age < 0 {
            age += SYNODIC_MONTH
        }
        return age
    }

    /// 便利方法：从 Date 计算月龄（内部转成 UTC 日历字段，与 Java 版行为一致）
    public static func moonAge(date: Date) -> Double {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return moonAge(year: c.year ?? 2000, month: c.month ?? 1, day: c.day ?? 1,
                       hour: c.hour ?? 0, minute: c.minute ?? 0)
    }

    /// 照明比例 0 ~ 1
    public static func moonIllumination(_ ageDays: Double) -> Double {
        return (1 - cos(2 * Double.pi * ageDays / SYNODIC_MONTH)) / 2.0
    }

    /// 月相名称（中文）
    public static func moonPhaseName(_ ageDays: Double) -> String {
        let p = ageDays / SYNODIC_MONTH
        if p < 0.0335 || p >= 0.9665 { return "新月（朔）" }
        if p < 0.2165 { return "娥眉月" }
        if p < 0.2835 { return "上弦月" }
        if p < 0.4665 { return "盈凸月" }
        if p < 0.5335 { return "满月（望）" }
        if p < 0.7165 { return "亏凸月" }
        if p < 0.7835 { return "下弦月" }
        return "残月"
    }

    /// 月相 emoji
    public static func moonPhaseEmoji(_ ageDays: Double) -> String {
        let p = ageDays / SYNODIC_MONTH
        if p < 0.0335 || p >= 0.9665 { return "🌑" }
        if p < 0.2165 { return "🌒" }
        if p < 0.2835 { return "🌓" }
        if p < 0.4665 { return "🌔" }
        if p < 0.5335 { return "🌕" }
        if p < 0.7165 { return "🌖" }
        if p < 0.7835 { return "🌗" }
        return "🌘"
    }

    /// 今日作钓启发式评分（0-100）。
    /// 依据：新月/满月前后通常鱼口更活跃，弦月期间相对平淡。
    /// 这是经验性参考值，不是科学预测，UI 中已标注"参考"。
    public static func fishActivityScore(_ moonAgeDays: Double) -> Int {
        let d = min(moonAgeDays, SYNODIC_MONTH - moonAgeDays)
        let dFull = abs(moonAgeDays - SYNODIC_MONTH / 2.0)
        let near = min(d, dFull)
        if near <= 2 { return 85 }
        if near <= 4 { return 70 }
        if near <= 7 { return 55 }
        return 45
    }

    // MARK: - 工具

    /// 一年中的第几天
    public static func dayOfYear(_ year: Int, _ month: Int, _ day: Int) -> Int {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone.current
        var c = DateComponents()
        c.year = year
        c.month = month
        c.day = day
        guard let date = cal.date(from: c) else { return 0 }
        return cal.ordinality(of: .day, in: .year, for: date) ?? 0
    }

    /// 小时的小数 -> "HH:mm"
    public static func hourToTime(_ hour: Double) -> String {
        if hour.isNaN {
            return "--:--"
        }
        var h = Int(hour.rounded(.down))
        // Java: (int) Math.round((hour - h) * 60) —— wrap 语义与 .rounded() 在此等价
        var m = Int(((hour - Double(h)) * 60).rounded())
        if m == 60 {
            m = 0
            h += 1
        }
        h %= 24
        return String(format: "%02d:%02d", h, m)
    }

    private static func norm360(_ v: Double) -> Double {
        var r = v.truncatingRemainder(dividingBy: 360.0)
        if r < 0 {
            r += 360.0
        }
        return r
    }

    private static func normHour(_ h: Double) -> Double {
        var r = h.truncatingRemainder(dividingBy: 24.0)
        if r < 0 {
            r += 24.0
        }
        return r
    }
}
