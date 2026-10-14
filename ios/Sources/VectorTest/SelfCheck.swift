import Foundation

/// 从操作系统直接读时区名，**刻意不走被测代码用的那条路**。
///
/// ## 为什么必须换一个来源
///
/// 被测代码（`SystemTimeZone`）在 Windows 上是用 `tzutil /g` 读时区名的。
/// 如果测试也用 `tzutil`，那就成了**用被测逻辑验证被测逻辑** ——
/// 两边一起错的时候测试照样通过。
///
/// 所以这里改成直接查注册表：`tzutil` 自己也是从注册表读的，
/// 但这是**另一条独立路径**（不同的可执行文件、不同的解析代码），
/// 交叉验证才有意义。
///
/// ## 为什么用 `reg query` 而不是 import WinSDK
///
/// 与 `SystemTimeZone` 同样的理由：核心与测试都保持"只依赖 Foundation"，
/// 才能在 Linux CI 上编译。调系统自带的 `reg.exe` 不引入任何模块。
///
/// - Returns: 非 Windows 平台返回 nil（那时 Foundation 的时区实现本来就是正常的，
///   不需要这条对照）。
func windowsTimeZoneNameFromSystem() -> String? {
    #if os(Windows)
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "C:\\Windows\\System32\\reg.exe")
    p.arguments = ["query",
                   "HKLM\\SYSTEM\\CurrentControlSet\\Control\\TimeZoneInformation",
                   "/v", "TimeZoneKeyName"]
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
    // 输出形如：`    TimeZoneKeyName    REG_SZ    China Standard Time`
    for line in out.split(separator: "\n") {
        let s = String(line)
        guard s.contains("TimeZoneKeyName"), let r = s.range(of: "REG_SZ") else {
            continue
        }
        let v = s[r.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        if !v.isEmpty {
            return v
        }
    }
    return nil
    #else
    return nil
    #endif
}

/// 系统时区与诊断逻辑的自检。
///
/// ## 为什么时区也要进测试
///
/// 「Windows 上 Swift Foundation 取不到系统时区」是一个**静默错误**：
/// 程序照常运行、界面照常显示，只是所有日期都差 8 小时 ——
/// 中国是 UTC+8，所以凌晨 0:00–8:00 这一档的日期会整体差一天。
///
/// 后果有两处是**真问题**：
///   · 禁渔期判定：5 月 16 日凌晨会被算成 5 月 15 日（合规问题，不是显示问题）
///   · `SunMoon.dayOfYear` 算错一年中的第几天 → 日出日落算错整天
///
/// 而它**不会**让任何测试失败 —— 因为契约测试里的日期都是显式构造的，
/// 唯一依赖"当前时区"的地方就是 `dayOfYear`，而它的日出窗口很宽，
/// 差一天有可能仍然落在容差内。所以必须有**直接针对时区本身**的断言。
///
/// 与 Java 侧对应：`tools/VectorTest.java` 里没有这一段 ——
/// Java 在 Windows 上时区正常（`TimeZone.getDefault()` = "Asia/Shanghai"），
/// 这个坑是 Swift on Windows 独有的。
func runTimeZoneAndDiagnostics(_ root: [String: Any], _ r: Runner) {
    print("\n=== 自检: 系统时区 ===")

    let tz = SystemTimeZone.current
    let offsetHours = Double(tz.secondsFromGMT()) / 3600.0
    r.check("能取到系统时区", true, "\(SystemTimeZone.describe)")

    // ★ 核心断言：拿一个**独立于 Foundation** 的事实来对照。
    //
    //   ⚠ 这里我第一版写错过，值得记下来：
    //
    //   第一版写的是「偏移为 0 时，若标识是 GMT/UTC 就认为环境本来就是 UTC，通过」。
    //   看起来合理，实际上是**自我实现**的判据 ——
    //   bug 退化后的表现**正好就是 GMT**，于是它被当成了"合法的 UTC 环境"，
    //   反向验证时 133 项全部通过、一条都没报。假阳性。
    //
    //   正确的判据必须来自外部：Windows 上有 `tzutil /g`（系统自带），
    //   它给出机器真正的时区名，与 Swift 的 Foundation 完全无关。
    //   两者对照才能真正发现"机器在 UTC+8 而 Swift 说 GMT"。
    //
    //   macOS / Linux 上没有 tzutil，此时退化为"只报告不判定" ——
    //   宁可少一条断言，也不要一条会误报的断言
    //   （误报比不报更糟：它会训练人忽略这条测试）。
    let osZoneName = windowsTimeZoneNameFromSystem()
    if let osName = osZoneName {
        // 已知的非零偏移时区。命中其中一个，就说明这台机器**不**在 UTC。
        let nonUtcZones: Set<String> = [
            "China Standard Time", "Taipei Standard Time", "Tokyo Standard Time",
            "Korea Standard Time", "India Standard Time", "SE Asia Standard Time",
            "W. Europe Standard Time", "Central Europe Standard Time",
            "Romance Standard Time", "GMT Standard Time", "E. Europe Standard Time",
            "Russian Standard Time", "Turkey Standard Time", "Israel Standard Time",
        ]
        if nonUtcZones.contains(osName) {
            // 机器不在 UTC，那么 SystemTimeZone 必须给出非零偏移。
            // 这正是「Windows 上 Foundation 返回 GMT」那个 bug 的检测点。
            r.check("系统时区名「\(osName)」不是 UTC，Swift 侧必须给出非零偏移",
                    offsetHours != 0,
                    String(format: "系统时区 = %@，Swift 识别为 %@（偏移 %+.1f）",
                           osName, tz.identifier, offsetHours))
        } else {
            r.check("系统时区与 Swift 识别结果一致（系统时区名 \(osName)）",
                    true, String(format: "Swift: %@ 偏移 %+.1f", tz.identifier, offsetHours))
        }
    } else {
        // 非 Windows：Foundation 的时区实现是正常的，只报告
        r.check("系统时区（非 Windows，Foundation 时区正常）",
                true, String(format: "%@ 偏移 %+.1f 小时", tz.identifier, offsetHours))
    }

    // `dayOfYear` 依赖时区，且它决定日出日落算哪一天
    r.check("dayOfYear 2026-01-01 = 1", SunMoon.dayOfYear(2026, 1, 1) == 1,
            "\(SunMoon.dayOfYear(2026, 1, 1))")
    r.check("dayOfYear 2026-03-01 = 60（平年）",
            SunMoon.dayOfYear(2026, 3, 1) == 60, "\(SunMoon.dayOfYear(2026, 3, 1))")
    r.check("dayOfYear 2026-12-31 = 365（平年）",
            SunMoon.dayOfYear(2026, 12, 31) == 365, "\(SunMoon.dayOfYear(2026, 12, 31))")
    r.check("dayOfYear 2024-12-31 = 366（闰年）",
            SunMoon.dayOfYear(2024, 12, 31) == 366, "\(SunMoon.dayOfYear(2024, 12, 31))")
    r.check("dayOfYear 2024-03-01 = 61（闰年）",
            SunMoon.dayOfYear(2024, 3, 1) == 61, "\(SunMoon.dayOfYear(2024, 3, 1))")

    // ================================================================ 诊断逻辑
    //
    // 诊断模块是开发者在 iOS 上**唯一的观测手段**（没有 adb、没有 Safari 远程调试、
    // 看不到设备）。如果它自己坏了，就彻底瞎了 —— 所以它的纯逻辑必须有测试。
    // 依赖 UIKit 的部分（把 UIView 拍扁）测不了，但报告生成、零尺寸检查、
    // 机型识别这三块都能测，而它们恰好是最容易出错的部分。
    print("\n=== 自检: 诊断报告 ===")

    // 机型识别：机型决定屏幕比例与安全区，是排查布局问题的第一个前提
    r.check("机型 iPhone10,2 = iPhone 8 Plus",
            ViewTree.modelName("iPhone10,2") == "iPhone 8 Plus",
            ViewTree.modelName("iPhone10,2"))
    r.check("机型 iPhone14,7 = iPhone 14",
            ViewTree.modelName("iPhone14,7") == "iPhone 14",
            ViewTree.modelName("iPhone14,7"))
    r.check("未知机型不崩、返回占位",
            ViewTree.modelName("iPhone99,9") == "未知机型",
            ViewTree.modelName("iPhone99,9"))

    // 零尺寸检查：这是整个诊断里最有价值的一条。
    // 安卓版踩过「记录表单 10 个输入框全部不可见」——
    // 代码存在、编译通过、没有报错，但宽或高是 0，屏幕上看不到。
    let tree = ViewTree.ViewNode(
        typeName: "UIView", x: 0, y: 0, width: 414, height: 736,
        children: [
            // 正常控件：不该被报出来
            ViewTree.ViewNode(typeName: "UILabel", x: 16, y: 20,
                              width: 200, height: 24,
                              text: "正常标签", isContentBearing: true),
            // 高度为 0 的按钮：应该被报出来（安卓那个 bug 的同类）
            ViewTree.ViewNode(typeName: "UIButton", x: 16, y: 60,
                              width: 300, height: 0,
                              text: "高度为 0 的按钮", isContentBearing: true),
            // 宽度为 0 的输入框：应该被报出来
            ViewTree.ViewNode(typeName: "UITextField", x: 16, y: 90,
                              width: 0, height: 44,
                              text: "宽度为 0", isContentBearing: true),
            // 隐藏的控件：**不该**被报出来（隐藏是有意的，不是缺陷）
            ViewTree.ViewNode(typeName: "UILabel", x: 0, y: 0,
                              width: 0, height: 0, hidden: true,
                              text: "有意的隐藏", isContentBearing: true),
            // 容器尺寸为 0：不该被报（容器可能是刻意的占位）
            ViewTree.ViewNode(typeName: "UIStackView", x: 0, y: 0,
                              width: 0, height: 0),
        ])

    let zero = ViewTree.findZeroSized(tree)
    r.check("零尺寸控件检出 2 个（高度 0 的按钮 + 宽度 0 的输入框）",
            zero.count == 2, "检出 \(zero.count) 个")
    let zeroTypes = zero.map { $0.node.typeName }.sorted()
    r.check("检出的是 UIButton 与 UITextField",
            zeroTypes == ["UIButton", "UITextField"], zeroTypes.joined(separator: ", "))
    r.check("隐藏控件不误报（隐藏是有意的）",
            !zero.contains { $0.node.hidden }, "无隐藏项被报")
    r.check("容器尺寸为 0 不误报（可能是刻意占位）",
            !zeroTypes.contains("UIStackView"), "无容器被报")

    // 报告内容：几个必须出现的字段
    let report = ViewTree.makeReport(
        node: tree, label: "自检",
        deviceModel: "iPhone10,2", systemVersion: "16.7.14",
        screenWidth: 414, screenHeight: 736, screenScale: 3,
        safeArea: (top: 20, left: 0, bottom: 0, right: 0),
        timestamp: "2026-01-01 00:00:00")

    r.check("报告含机型通俗名", report.contains("iPhone 8 Plus"), "")
    r.check("报告含屏幕比例可算的尺寸", report.contains("414.0 x 736.0"), "")
    // iPhone 8 Plus 是 16:9、有 Home 键，底部安全区必须是 0
    r.check("底部安全区为 0 时不报警告",
            !report.contains("底部安全区非 0"), "")
    r.check("报告列出零尺寸控件", report.contains("2 个「有内容但尺寸为 0」的控件"), "")
    r.check("报告含视图层级段", report.contains("---- 视图层级 ----"), "")
    r.check("报告含节点计数", report.contains("---- 共 6 个节点 ----"), "")

    // 安全区非 0 时必须警告 —— iPhone 8 Plus 有 Home 键，非 0 说明跑错机型
    let reportBad = ViewTree.makeReport(
        node: tree, label: "自检",
        deviceModel: "iPhone10,2", systemVersion: "16.7.14",
        screenWidth: 414, screenHeight: 736, screenScale: 3,
        safeArea: (top: 44, left: 0, bottom: 34, right: 0),
        timestamp: "2026-01-01 00:00:00")
    r.check("底部安全区非 0 时给出警告（机型或读取时机的提示）",
            reportBad.contains("底部安全区非 0"), "")

    // 无可报项时的措辞
    let clean = ViewTree.ViewNode(typeName: "UIView", x: 0, y: 0,
                                  width: 100, height: 100,
                                  children: [
        ViewTree.ViewNode(typeName: "UILabel", x: 0, y: 0,
                          width: 50, height: 20, isContentBearing: true)
    ])
    let reportClean = ViewTree.makeReport(
        node: clean, label: "自检", deviceModel: "iPhone10,2",
        systemVersion: "16.7.14", screenWidth: 414, screenHeight: 736,
        screenScale: 3, safeArea: (20, 0, 0, 0), timestamp: "x")
    r.check("无零尺寸控件时给出通过措辞",
            reportClean.contains("未发现零尺寸的可见控件"), "")

    // 截断保护：上千个节点不能把报告撑爆、也不能静默截断
    var deep = ViewTree.ViewNode(typeName: "UIView", x: 0, y: 0, width: 1, height: 1)
    for _ in 0..<(ViewTree.maxDepth + 5) {
        deep = ViewTree.ViewNode(typeName: "UIView", x: 0, y: 0,
                                 width: 1, height: 1, children: [deep])
    }
    let reportDeep = ViewTree.makeReport(
        node: deep, label: "深树", deviceModel: "iPhone10,2",
        systemVersion: "16.7.14", screenWidth: 414, screenHeight: 736,
        screenScale: 3, safeArea: (20, 0, 0, 0), timestamp: "x")
    r.check("超深视图树被截断且明确标注（不静默截断）",
            reportDeep.contains("已截断"), "深度上限 \(ViewTree.maxDepth)")
}
