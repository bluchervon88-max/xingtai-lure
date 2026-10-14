import UIKit

/// 界面诊断：把视图层级拍扁、写进文件，供开发者在 Windows 上查看。
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
///
/// 安卓版有几个 bug 是靠 `uiautomator dump` 才定位的。最典型的一个：
/// 「记录作钓」表单里 **10 个输入框全部不可见** —— 代码看着没问题、编译也过了，
/// 但实测出来是 `EditText 宽 931px 高 0px`，直接指向了布局参数的写法错误。
///
/// **iOS 上没有这套工具，所以必须让 App 自己把同样的信息写出来。**
///
/// ## 这个文件为什么这么薄
///
/// 真正有内容的逻辑（视图树的数据结构、报告格式化、零尺寸控件检查、机型识别）
/// 都在 `ViewTree.swift` 里 —— 那里不依赖 UIKit，能在 Linux CI 和开发机上直接测试。
///
/// 本文件只留两件**只能在真机上做**的杂事：
///   1. 把 `UIView` 树拍扁成 `ViewTree.ViewNode`
///   2. 写文件
///
/// 这样拆分的原因：这个模块是开发者在 iOS 上**唯一的观测手段**。
/// 如果它自己崩了，就彻底瞎了 —— 而这时连"哪里错了"都无从下手。
/// 把逻辑拆出去做测试，等于提前验证了这只"眼睛"能不能看清东西，
/// 只留下"取 UIView 属性"这一步真正的未知。
///
/// ## 怎么把数据取出来（用户侧，不需要 Mac、不需要数据线）
///
/// Info.plist 里开了 `UIFileSharingEnabled`，所以：
///
///     手机 →「文件」App →「我的 iPhone」→ XtLure → layout-dump.txt
///
/// 直接把文件发给开发者即可。也可以点界面上的「诊断」按钮就地查看、截图。
enum Diag {

    /// 诊断文件名。固定名（而不是带时间戳）是有意的：
    /// 用户只需要认准一个文件，每次跑完覆盖，不会在「文件」App 里堆一屏。
    static let fileName = "layout-dump.txt"

    /// 崩溃与事件日志文件名
    static let crashFileName = "crash.log"

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
        let screen = UIScreen.main
        let bounds = screen.bounds
        let insets = root.safeAreaInsets

        return ViewTree.makeReport(
            node: flatten(root),
            label: label,
            deviceModel: rawDeviceIdentifier(),
            systemVersion: UIDevice.current.systemVersion,
            screenWidth: Double(bounds.width),
            screenHeight: Double(bounds.height),
            screenScale: Double(screen.scale),
            safeArea: (Double(insets.top), Double(insets.left),
                       Double(insets.bottom), Double(insets.right)),
            timestamp: timestamp())
    }

    // MARK: - UIView -> ViewNode

    /// 把 UIView 树拍扁成纯数据的视图树。
    ///
    /// 这一步是**唯一依赖 UIKit** 的地方，也是唯一没被测试覆盖的地方
    /// （Windows 上没有 UIKit，CI 上也没有模拟器）。
    /// 所以这里刻意写得直白、不做任何优化 —— 好读、好排查，
    /// 而不是聪明但难验证。
    static func flatten(_ v: UIView, depth: Int = 0) -> ViewTree.ViewNode {
        let f = v.frame

        // 文字内容：三种控件取法不同，统一放进一个字段
        var text: String?
        if let lb = v as? UILabel {
            text = lb.text
        } else if let bt = v as? UIButton {
            text = bt.title(for: .normal)
        } else if let tf = v as? UITextField {
            text = tf.placeholder
        }

        // 只有滚动视图才有内容高度这个概念
        var contentH: Double?
        if let sv = v as? UIScrollView, sv.isScrollEnabled {
            contentH = Double(sv.contentSize.height)
        }

        // 哪些类型算"有内容"——决定要不要参与零尺寸检查。
        // 只列这四种：它们的尺寸为 0 一定是缺陷。
        // 容器类（UIStackView / UIView）尺寸为 0 有可能是刻意的占位。
        let contentBearing = (v is UILabel) || (v is UIButton)
            || (v is UITextField) || (v is UIImageView)

        // 深度保护：超过上限就不再往下走，避免在异常层级上把栈打爆
        var kids: [ViewTree.ViewNode] = []
        if depth < ViewTree.maxDepth {
            kids = v.subviews.map { flatten($0, depth: depth + 1) }
        }

        return ViewTree.ViewNode(
            typeName: String(describing: type(of: v)),
            x: Double(f.minX), y: Double(f.minY),
            width: Double(f.width), height: Double(f.height),
            hidden: v.isHidden,
            transparent: Double(v.alpha) < 0.05,
            scrollContentHeight: contentH,
            text: text,
            isContentBearing: contentBearing,
            children: kids)
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

    /// 追加一条记录（不覆盖，便于看历史）。
    ///
    /// 启动痕迹、致命错误都走这里 —— 诊断文件是每次布局后**覆盖**的，
    /// 如果 App 在布局之前就崩，至少这里能留下线索。
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

    /// 取设备机型标识（如 `iPhone10,2` = iPhone 8 Plus）。
    ///
    /// 用 `uname` 的系统调用而不是 `UIDevice.current.model` ——
    /// 后者只会返回 "iPhone"，区分不出具体机型。
    /// 而机型直接决定屏幕比例与安全区，是排查布局问题的第一个前提。
    static func rawDeviceIdentifier() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        return withUnsafeBytes(of: &systemInfo.machine) { raw -> String in
            let bytes = raw.prefix(while: { $0 != 0 })
            return String(bytes: bytes, encoding: .utf8) ?? "?"
        }
    }

    /// 机型的可读描述，例如 `iPhone10,2 (iPhone 8 Plus)`。
    ///
    /// ★ 为什么要有这个成员：原来调用方直接写 `Diag.deviceModel()`，
    ///   重构时那个方法被移进 `ViewTree`，两个调用点就断了 ——
    ///   编译报 `type 'Diag' has no member 'deviceModel'`，一路推到 CI 才发现。
    ///
    ///   现在提供一个**语义稳定**的入口：内部用哪个类型、叫什么名字都可以变，
    ///   调用方只依赖 `Diag.deviceDescription` 这一个名字。
    ///   界面层也不需要为了拼一个机型名去 import 核心层的类型。
    static var deviceDescription: String {
        let id = rawDeviceIdentifier()
        return "\(id) (\(ViewTree.modelName(id)))"
    }
}
