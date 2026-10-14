import Foundation

/// 系统时区（带平台兼容层）。
///
/// ## 这个文件为什么存在
///
/// **Windows 上的 Swift Foundation 取不到系统时区，一律返回 GMT。**
/// 实测（本机 Windows 时区 = China Standard Time）：
///
/// ```
/// TimeZone.current.identifier            = "GMT"
/// TimeZone.current.secondsFromGMT()      = 0
/// NSTimeZone.system / .local / .default  → 全是 "GMT"
/// Calendar.current.timeZone.identifier   = "GMT"
///
/// 同一台机器上的 Java:
/// TimeZone.getDefault().getID()          = "Asia/Shanghai"   （偏移 8 小时）
/// ```
///
/// 这不是"配置问题"，是 corelibs-foundation 在 Windows 上没实现系统时区查找。
/// 后果是**同一时刻在两边的日期可能差一天**（中国 UTC+8，凌晨 0:00–8:00 这一档）：
///
/// · 导出的 `timeText` 比安卓少 8 小时
/// · **禁渔期判定差一天** —— 5 月 16 日凌晨 0:00–8:00 会被算成 5 月 15 日，
///   7 月 31 日同理。这是合规问题，不是显示问题
/// · `SunMoon.dayOfYear`（算一年中第几天）会算错，进而影响日出日落
///
/// ## 为什么单独一个文件，而不是塞进 Exporter
///
/// 一开始是在 `Exporter.swift` 里加的，但这样有隐患：
/// 模型层（`FishingSpot` 的禁渔期判定、`SunMoon` 的日期换算）也要用系统时区，
/// 让它们去依赖导出层是**反向依赖**。
/// 单独成一个文件后，谁需要谁引用，没有层次倒置。
///
/// ## 为什么不用 WinSDK（省一次依赖，也保住跨平台编译）
///
/// 最初的实现用了 `#if canImport(WinSDK) && os(Windows) import WinSDK` 读注册表。
/// 两个问题：
///   1. Linux CI 上没有 WinSDK —— 虽然 `#if` 会跳过，但多一个平台专属模块终究是负担
///   2. 破坏了本工程"核心层不依赖任何非 Foundation 模块"的约定
///      （这条约定是核心层能在 Linux 上编译和测试的前提）
///
/// 改用**不引任何额外模块**的办法读时区：调用系统自带的 `tzutil.exe`，
/// 拿到的名字（如 `China Standard Time`）再映射成 IANA 名。
/// `tzutil` 是 Windows 自带的命令行工具，不需要额外依赖。
///
/// ## 精度上的取舍
///
/// 映射表把 Windows 时区名对应到 IANA 名，因此**保留夏令时规则**
/// （例如 `Pacific Standard Time` → `America/Los_Angeles`，
/// 比只记一个固定偏移正确）。表只列常用时区，查不到时退回 `TimeZone.current` ——
/// 宁可差 8 小时也不要崩，而且诊断报告里会体现出来。
public enum SystemTimeZone {

    /// 当前系统时区。
    ///
    /// 各平台的取值方式：
    ///   · Apple 平台（iOS / macOS）：Foundation 正常工作，直接用 `TimeZone.current`
    ///   · Linux：Foundation 也正常（读 `/etc/localtime`）
    ///   · Windows：Foundation 返回 GMT，必须自己查（见本文件说明）
    public static var current: TimeZone {
        #if os(Windows)
        return windowsTimeZone() ?? TimeZone.current
        #else
        return TimeZone.current
        #endif
    }

    /// 供诊断用的描述：当前到底识别成了哪个时区、偏移多少。
    ///
    /// 这个字符串会出现在诊断报告里。因为"时区取错了"是一种**静默错误**——
    /// 界面照常显示，只是日期差一天，不主动暴露的话很难发现。
    /// 把识别结果写出来，用户一眼就能看出对不对（例如不该是 GMT）。
    public static var describe: String {
        let tz = current
        let hours = Double(tz.secondsFromGMT()) / 3600.0
        var s = String(format: "%@ (UTC%+.1f)", tz.identifier, hours)
        #if os(Windows)
        if tz.identifier == "GMT" {
            s += "  ⚠ Windows 上未能识别系统时区，日期可能差一天"
        }
        #endif
        return s
    }

    // MARK: - Windows

    #if os(Windows)
    /// 通过 `tzutil /g` 取 Windows 时区名，再映射成 IANA 名。
    ///
    /// 用 `Process` 调系统自带工具，而不是 import WinSDK 读注册表：
    /// 少一个平台专属模块，核心层仍然是"只有 Foundation"。
    private static func windowsTimeZone() -> TimeZone? {
        guard let name = runTzutil() else {
            return nil
        }
        if let iana = windowsToIana[name], let tz = TimeZone(identifier: iana) {
            return tz
        }
        // 映射表里没有：至少试试直接当 IANA 名用（某些配置下 tzutil 也会返回 IANA 名）
        if let tz = TimeZone(identifier: name) {
            return tz
        }
        return nil
    }

    private static func runTzutil() -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "C:\\Windows\\System32\\tzutil.exe")
        p.arguments = ["/g"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = Pipe()
        do {
            try p.run()
            p.waitUntilExit()
        } catch {
            return nil
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        guard let out = String(data: data, encoding: .utf8) else {
            return nil
        }
        let trimmed = out.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Windows 时区名 → IANA 名。
    ///
    /// 为什么需要映射：`TimeZone(identifier: "China Standard Time")` **返回 nil**，
    /// Foundation 只认 IANA 名（`Asia/Shanghai`）。
    ///
    /// 为什么映射到 IANA 名而不是记一个固定偏移：
    /// 有夏令时的地区用 IANA 名才能算对（固定偏移会在夏令时切换时错一小时）。
    /// 中国没有夏令时，但表里其它时区有。
    ///
    /// 表只列常用的。查不到返回 nil，由调用方退回 `TimeZone.current`。
    private static let windowsToIana: [String: String] = [
        // 中国
        "China Standard Time": "Asia/Shanghai",
        "Taipei Standard Time": "Asia/Taipei",
        "Hong Kong Standard Time": "Asia/Hong_Kong",
        "Singapore Standard Time": "Asia/Singapore",
        "Tokyo Standard Time": "Asia/Tokyo",
        "Korea Standard Time": "Asia/Seoul",
        // 亚洲其它
        "India Standard Time": "Asia/Kolkata",
        "SE Asia Standard Time": "Asia/Bangkok",
        "Myanmar Standard Time": "Asia/Yangon",
        "Nepal Standard Time": "Asia/Kathmandu",
        "Pakistan Standard Time": "Asia/Karachi",
        "Bangladesh Standard Time": "Asia/Dhaka",
        "Arabian Standard Time": "Asia/Dubai",
        "Iran Standard Time": "Asia/Tehran",
        "Israel Standard Time": "Asia/Jerusalem",
        // 欧洲
        "GMT Standard Time": "Europe/London",
        "W. Europe Standard Time": "Europe/Berlin",
        "Central Europe Standard Time": "Europe/Budapest",
        "Romance Standard Time": "Europe/Paris",
        "E. Europe Standard Time": "Europe/Chisinau",
        "FLE Standard Time": "Europe/Kiev",
        "Russian Standard Time": "Europe/Moscow",
        "Turkey Standard Time": "Europe/Istanbul",
        // 美洲
        "Eastern Standard Time": "America/New_York",
        "Central Standard Time": "America/Chicago",
        "Mountain Standard Time": "America/Denver",
        "Pacific Standard Time": "America/Los_Angeles",
        "Alaskan Standard Time": "America/Anchorage",
        "Hawaiian Standard Time": "Pacific/Honolulu",
        "Atlantic Standard Time": "America/Halifax",
        "SA Pacific Standard Time": "America/Bogota",
        "E. South America Standard Time": "America/Sao_Paulo",
        "Argentina Standard Time": "America/Argentina/Buenos_Aires",
        // 大洋洲 / 非洲
        "AUS Eastern Standard Time": "Australia/Sydney",
        "New Zealand Standard Time": "Pacific/Auckland",
        "South Africa Standard Time": "Africa/Johannesburg",
        "Egypt Standard Time": "Africa/Cairo",
        // UTC 本身
        "UTC": "UTC",
        "Coordinated Universal Time": "UTC",
    ]
    #endif
}
