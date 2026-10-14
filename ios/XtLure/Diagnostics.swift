import UIKit

/// 界面诊断：把视图层级和实测尺寸写成文件，供开发者在 Windows 上查看。
///
/// ## 为什么需要这个文件
///
/// 开发这个 App 的机器是 **Windows**，而 iOS 开发环境只有 macOS 有。这意味着
/// 开发者**看不到 iPhone 上的任何东西**：
///
/// | | 安卓（本项目已经做完的版本） | iOS |
/// |---|---|---|
/// | 截图 | `adb shell screencap` | ✗ |
/// | 界面层级 | `adb shell uiautomator dump` | ✗ |
/// | 模拟点击 | `adb shell input tap` | ✗ |
/// | 运行日志 | `adb logcat` | ✗ |
/// | 实时调试 | Chrome DevTools 式 | ✗ |
///
/// 安卓版有几个 bug 是靠 `uiautomator dump` 才定位的 —— 最典型的是
/// 「记录作钓表单里 10 个输入框全部不可见」：代码看着没问题，编译也过了，
/// 但实测出来是 `EditText 宽 931px 高 0px`，直接指向了 `LayoutParams` 的写法错误。
///
/// **iOS 上没有这套工具，所以必须让 App 自己把同样的信息写出来。**
/// 这个文件就是干这个的：替代 `uiautomator dump`。
///
/// ## 怎么把数据取出来（用户侧，不需要 Mac、不需要数据线）
///
/// Info.plist 里开了 `UIFileSharingEnabled`，所以：
///
///     手机 →「文件」App →「我的 iPhone」→ XtLure → layout-dump.txt
///
/// 直接把文件发给开发者即可。也可以点界面上的「诊断」按钮就地查看、截图。
///
/// ## 写出来的内容长什么样
///
/// ```
/// ==== 布局实测 2026-10-07 18:30:12 ====
/// 设备: iPhone 8 Plus / iOS 16.7.14
/// 屏幕: 414.0 x 736.0 pt  (scale 3.0, native 1242x2208)
/// 安全区: top=20.0 bottom=0.0  (有 Home 键 -> bottom 应为 0)
///
/// UIWindow 414.0x736.0 @ (0.0, 0.0)
///   UILayoutContainerView 414.0x736.0 @ (0.0, 0.0)
///     MainViewController.view 414.0x736.0 @ (0.0, 0.0)
///       UIScrollView 414.0x692.0 @ (0.0, 44.0)   [可滚动]
///         ...
///   ⚠ 高度为 0 的视图（这些在界面上看不见）:
///     UILabel '距离' 宽 0.0 高 0.0
/// ```
///
/// 最后那个「高度为 0 的视图」检查就是专门为抓上面说的那类 bug 设计的：
/// **控件存在、代码没报错、但用户看不见** —— 这是最难自查的一类缺陷。
enum Diag {

    /// 诊断文件名。固定名（而不是带时间戳）是有意的：
    /// 用户只需要认准一个文件，每次跑完覆盖，不会在「文件」App 里堆一屏。
    static let fileName = "layout-dump.txt"

    /// 崩溃日志文件名
    static let crashFileName = "crash.log"

    /// 视图树最大遍历深度与节点数上限。
    /// 73 张钓点卡片会让节点数上千，dump 太大会卡住主线程，
    /// 所以截断并明确标注"已截断"，而不是让它悄悄变慢。
    private static let maxDepth = 40
    private static let maxNodes = 4000

    // MARK: - 公共入口

    /// 在视图布局完成后挂一次诊断。
    ///
    /// 用 `DispatchQueue.main.async` 延后一拍，是为了拿到**最终**尺寸 ——
    /// 直接调用的话约束可能还没求解完，量出来的都是 0，
    /// 那样写出来的报告毫无价值（而且会误导排查方向）。
    static func attach(to view: UIView, label: String) {
        view.layoutIfNeeded()
        DispatchQueue.main.async {
            let text = report(for: view, label: label)
            write(text)
        }
    }

    /// 生成完整报告文本（也用于界面上的「诊断」页就地展示）
    static func report(for root: UIView, label: String) -> String {
        var out: [String] = []
        let screen = UIScreen.main
        let bounds = screen.bounds

        out.append("==== 布局实测 \(timestamp()) ====")
        out.append("标记: \(label)")
        out.append("设备: \(deviceModel()) / iOS \(UIDevice.current.systemVersion)")
        out.append(String(format: "屏幕: %.1f x %.1f pt  (scale %.1f, 原生 %.0fx%.0f)",
                          bounds.width, bounds.height, screen.scale,
                          bounds.width * screen.scale, bounds.height * screen.scale))

        // 安全区：iPhone 8 Plus 有 Home 键，bottom 应该是 0。
        // 如果这里出现非 0，说明跑在了别的机型或者布局假设错了。
        if #available(iOS 11.0, *) {
            let insets = root.safeAreaInsets
            out.append(String(format: "安全区: top=%.1f left=%.1f bottom=%.1f right=%.1f",
                              insets.top, insets.left, insets.bottom, insets.right))
            if insets.bottom > 1 {
                out.append("  ⚠ 底部安全区非 0 —— 这台设备有 Home 键，正常情况下应为 0。")
                out.append("    若非 0，可能是跑在了全面屏机型上，或 safeAreaInsets 读取时机过早。")
            }
        }

        // 关键的"看不见的控件"检查，放在树前面 —— 这是最需要被看到的信息
        var zeroSized: [String] = []
        var nodeCount = 0
        var truncated = false

        out.append("")
        out.append("---- 视图层级 ----")

        func walk(_ v: UIView, depth: Int) {
            guard depth <= maxDepth, nodeCount < maxNodes else {
                truncated = true
                return
            }
            nodeCount += 1

            let f = v.frame
            var line = String(repeating: "  ", count: depth)
            line += "\(type(of: v)) "
            line += String(format: "%.1fx%.1f @ (%.1f, %.1f)", f.width, f.height, f.minX, f.minY)

            if v.isHidden {
                line += " [隐藏]"
            }
            if v.alpha < 0.05 {
                line += " [透明]"
            }
            if let sv = v as? UIScrollView, sv.isScrollEnabled {
                line += " [可滚动 内容高 \(Int(sv.contentSize.height))]"
            }
            if let lb = v as? UILabel, let t = lb.text, !t.isEmpty {
                line += " '\(t.prefix(40))'"
            } else if let bt = v as? UIButton, let t = bt.title(for: .normal), !t.isEmpty {
                line += " 『\(t.prefix(40))』"
            } else if let tf = v as? UITextField, let t = tf.placeholder {
                line += " 占位'\(t.prefix(30))'"
            }

            // 记录"明明有内容却零尺寸"的视图 —— 用户看不见它们，但代码里存在
            let hasContent = (v as? UILabel)?.text?.isEmpty == false
                || v is UIButton
                || v is UITextField
                || v is UIImageView
            if hasContent && (f.width < 0.5 || f.height < 0.5) && !v.isHidden {
                zeroSized.append("\(type(of: v)) \(line.trimmingCharacters(in: .whitespaces))")
            }

            out.append(line)

            for sub in v.subviews {
                walk(sub, depth: depth + 1)
            }
        }

        walk(root, depth: 0)

        if truncated {
            out.append("… 已截断（深度超过 \(maxDepth) 或节点超过 \(maxNodes)）")
        }
        out.append("---- 共 \(nodeCount) 个节点 ----")

        out.append("")
        if zeroSized.isEmpty {
            out.append("✅ 未发现零尺寸的可见控件")
        } else {
            out.append("⚠ 发现 \(zeroSized.count) 个「有内容但尺寸为 0」的控件（在界面上看不见）：")
            for s in zeroSized.prefix(30) {
                out.append("   " + s)
            }
            if zeroSized.count > 30 {
                out.append("   … 还有 \(zeroSized.count - 30) 个")
            }
        }

        out.append("")
        return out.joined(separator: "\n")
    }

    // MARK: - 落盘

    /// 文档目录。开了 UIFileSharingEnabled 后，这个目录会出现在「文件」App 里。
    static var documentsURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// 写入诊断文件。失败时**不能崩、也不能静默** —— 退回控制台并留下痕迹。
    @discardableResult
    static func write(_ text: String) -> Bool {
        let url = documentsURL.appendingPathComponent(fileName)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return true
        } catch {
            NSLog("[XtLure] 诊断写入失败: \(error)")
            return false
        }
    }

    /// 追加一条崩溃记录（不覆盖，便于看历史）
    static func recordCrash(_ text: String) {
        let url = documentsURL.appendingPathComponent(crashFileName)
        let entry = "==== \(timestamp()) ====\n\(text)\n\n"
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            if let data = entry.data(using: .utf8) {
                handle.write(data)
            }
            handle.closeFile()
        } else {
            try? entry.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    // MARK: - 工具

    static func timestamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "zh_CN")
        return f.string(from: Date())
    }

    /// 取设备机型标识（如 iPhone10,2 = iPhone 8 Plus）。
    ///
    /// 用 `uname` 的系统调用而不是 `UIDevice.current.model` ——
    /// 后者只会返回 "iPhone"，区分不出具体机型，而机型直接决定屏幕比例
    /// （iPhone 8 Plus 是 16:9 且无刘海，新机型不是），对排查布局问题很关键。
    static func deviceModel() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafeBytes(of: &systemInfo.machine) { raw -> String in
            let bytes = raw.prefix(while: { $0 != 0 })
            return String(bytes: bytes, encoding: .utf8) ?? "?"
        }
        return "\(machine) (\(modelName(machine)))"
    }

    /// 机型标识 -> 通俗名称。只列与本项目相关的常见机型，其余原样返回。
    private static func modelName(_ id: String) -> String {
        let map: [String: String] = [
            "iPhone10,1": "iPhone 8",
            "iPhone10,2": "iPhone 8 Plus",
            "iPhone10,4": "iPhone 8",
            "iPhone10,5": "iPhone 8 Plus",
            "iPhone11,2": "iPhone XS",
            "iPhone12,1": "iPhone 11",
            "iPhone14,7": "iPhone 14",
            "iPhone15,4": "iPhone 15",
        ]
        return map[id] ?? "未知机型"
    }
}
