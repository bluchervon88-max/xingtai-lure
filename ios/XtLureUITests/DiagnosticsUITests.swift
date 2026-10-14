import XCTest

/// 诊断功能的端到端验证。
///
/// ## 为什么需要这个测试
///
/// 「诊断能不能用」这件事在开发机上**完全验证不了** —— Windows 上没有 UIKit。
/// 而它恰恰是整条 iOS 路线里最关键的一环：
///
/// > 开发机是 Windows，看不到 iPhone 屏幕。
/// > 诊断模块是**唯一**能把"界面实际长什么样"带回来的通道。
///
/// 如果它写不出文件，用户在真机上按了按钮也拿不到任何东西，
/// 而开发者只能干等一轮（一次真机往返 = 用户手动装 IPA + 操作 + 发文件）。
///
/// 所以这个测试在 CI 的 iOS 模拟器上**真正启动 App**，然后验证：
///
///   1. App 能启动且不崩溃        —— 崩溃的话 `XCUIApplication.launch()` 就失败
///   2. 诊断文件确实被写出来了     —— 这是核心
///   3. 文件内容结构正确          —— 含机型、屏幕、安全区、视图层级、零尺寸检查
///   4. 首屏该有的东西都在        —— 验证版首屏显示的环境信息与算法自检
///
/// 顺带把报告内容**打印到测试日志**里。这样 CI 日志中就能直接看到
/// 模拟器上的真实布局数据 —— 相当于在没有真机的时候先"看见"一次界面。
///
/// ## 为什么不用 `simctl` 从外面看
///
/// 试过这条路：`xcrun simctl get_app_container ... data` 能拿到 App 的数据目录。
/// ## 关于读取 App 写出的文件
///
/// UI 测试跑在**独立进程、独立沙箱**里，不是 App 的一部分。
/// 所以读诊断文件必须用 `XCUIApplication.containerURL` 拿到**被测 App** 的容器，
/// 不能用 `FileManager.default` —— 那会读到测试进程自己的目录。
///
/// 第一版就是踩了这个坑：测试报"诊断文件没有被写出来"、
/// 打印出的 Documents 内容是「（空）」，看起来像 App 没写文件，
/// 实际上文件写了、只是读错了地方。**这类失败会把人引向完全错误的方向。**
final class DiagnosticsUITests: XCTestCase {

    /// 被测 App。目标名与 scheme 里的 BlueprintName 一致。
    private var app: XCUIApplication!

    /// App 的 Documents 目录。
    ///
    /// ★ 必须用 `app.containerURL`，**不能**用
    ///   `FileManager.default.urls(for: .documentDirectory, ...)`。
    ///
    ///   UI 测试跑在**独立进程、独立沙箱**里，它不是 App 的一部分。
    ///   所以 `FileManager.default` 拿到的是**测试进程自己**的 Documents 目录，
    ///   而 App 写文件写的是 App 自己的目录 —— 两者是两个不同的路径。
    ///
    ///   实测踩到：第一版用了 FileManager.default，测试报
    ///   "诊断文件没有被写出来"，并打印出 Documents 内容为「（空）」——
    ///   看起来像 App 没写文件，实际上文件写了、只是读错了地方。
    ///
    ///   `XCUIApplication.containerURL` 是 XCTest 专门为此提供的接口。
    private var documentsDir: URL {
        app.containerURL.appendingPathComponent("Documents")
    }

    private var dumpURL: URL {
        documentsDir.appendingPathComponent("layout-dump.txt")
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 核心测试

    /// App 能启动，并且把诊断文件写出来了。
    ///
    /// 这一条是整个 iOS 路线的**关键前提**。它失败意味着：
    /// 用户装到真机上之后，开发者拿不到任何界面信息，等于闭着眼睛改界面。
    func testAppLaunchesAndWritesDiagnostic() throws {
        app = XCUIApplication()
        app.launch()

        // 等 App 稳定。启动本身崩溃的话，这一句就会失败。
        XCTAssertTrue(waitUntilForeground(app, timeout: 30),
                      "App 没有进入前台 —— 可能启动即崩溃")

        // 诊断是在首次布局之后异步写的（viewDidAppear -> DispatchQueue.main.async）。
        // 轮询等待，而不是 sleep 一个固定值：固定值要么慢、要么在慢机器上偶发失败。
        let wrote = waitForFile(dumpURL, timeout: 20)

        // 无论成功失败，先把已有信息打印出来，便于排查
        print("──────── 容器路径 ────────")
        print("Documents: \(documentsDir.path)")
        if FileManager.default.fileExists(atPath: documentsDir.path) {
            let names = (try? FileManager.default.contentsOfDirectory(
                atPath: documentsDir.path)) ?? []
            print("Documents 内容: \(names.isEmpty ? "（空）" : names.joined(separator: ", "))")
        } else {
            print("Documents 目录不存在")
        }
        // 崩溃日志也要看 —— 它可能解释了为什么诊断没写出来
        let crashURL = documentsDir.appendingPathComponent("crash.log")
        if let crash = try? String(contentsOf: crashURL, encoding: .utf8) {
            print("──────── crash.log ────────")
            print(crash)
        }

        XCTAssertTrue(wrote, "诊断文件没有被写出来: \(dumpURL.path)")
        guard wrote else { return }

        let report = try String(contentsOf: dumpURL, encoding: .utf8)
        print("──────── layout-dump.txt 全文（\(report.count) 字符）────────")
        print(report)
        print("──────── 报告结束 ────────")

        // ---- 报告结构检查
        XCTAssertTrue(report.contains("==== 布局实测"), "报告缺少标题行")
        XCTAssertTrue(report.contains("设备: "), "报告缺少设备行")
        XCTAssertTrue(report.contains("屏幕: "), "报告缺少屏幕尺寸")
        XCTAssertTrue(report.contains("安全区: "), "报告缺少安全区")
        XCTAssertTrue(report.contains("---- 视图层级 ----"), "报告缺少视图层级段")
        XCTAssertTrue(report.contains("个节点"), "报告缺少节点计数")

        // 机型与系统：模拟器上不是 iPhone10,2，但必须能识别出**某个**机型描述。
        // 不断言具体机型（CI 跑在不同模拟器上），只断言"识别逻辑有输出"。
        XCTAssertFalse(report.contains("设备:  (未知机型)"),
                       "机型识别返回了空标识")

        // 屏幕尺寸必须非零 —— 为零说明取尺寸的时机不对，整份报告就没意义了
        XCTAssertFalse(report.contains("屏幕: 0.0 x 0.0"),
                       "屏幕尺寸为 0，报告无效")

        // ---- 视图层级里必须出现首屏的关键控件
        //
        // 首屏是「验证版」：运行环境卡片 + 预置数据卡片 + 算法自检卡片 + 诊断按钮。
        // 这些文字应该能在视图树里找到（UILabel 的 text 会被记录下来）。
        for keyword in ["运行环境", "预置数据", "算法自检", "诊断"] {
            XCTAssertTrue(report.contains(keyword),
                          "视图层级里找不到首屏元素「\(keyword)」——"
                          + "可能界面没建起来，或者文字没被记录进报告")
        }

        // ---- 零尺寸控件检查
        //
        // 报告里这一段**必须存在**（无论有没有问题）。它是安卓版那个
        // 「10 个输入框全部不可见」bug 的同类检测手段，不能因为没触发就消失。
        let hasZeroSection = report.contains("未发现零尺寸的可见控件")
            || report.contains("有内容但尺寸为 0")
        XCTAssertTrue(hasZeroSection, "报告缺少零尺寸控件检查段")
    }

    /// 点「诊断」按钮不崩。
    ///
    /// 用户在真机上要点的就是它。前端崩溃意味着这条路走不通。
    func testDiagnoseButtonDoesNotCrash() throws {
        app = XCUIApplication()
        app.launch()
        XCTAssertTrue(waitUntilForeground(app, timeout: 30))

        // 按标题找按钮（界面上是「诊断」的 UIBarButtonItem）
        let button = app.buttons["诊断"]
        guard button.waitForExistence(timeout: 10) else {
            // 找不到按钮时把界面上有什么打出来 —— 这本身就是有用的诊断信息
            print("──────── 界面上的按钮 ────────")
            for i in 0..<app.buttons.count {
                print("  [\(i)] \(app.buttons.element(boundBy: i).label)")
            }
            XCTFail("找不到「诊断」按钮")
            return
        }

        button.tap()

        // 点完之后 App 必须还活着
        XCTAssertTrue(waitUntilForeground(app, timeout: 10),
                      "点了「诊断」之后 App 不在前台了 —— 可能崩溃")
        XCTAssertEqual(app.state, .runningForeground, "点「诊断」后 App 状态异常")
    }

    // MARK: - 工具

    /// 等 App 进入前台。
    ///
    /// ★ 不用 `XCUIApplication.waitForState(_:timeout:)` —— Xcode 16 起那个 API
    ///   被改名为 `wait(for:timeout:)`，用旧名会直接编译失败：
    ///
    ///     error: 'waitForState(_:timeout:)' has been renamed to 'wait(for:timeout:)'
    ///
    ///   而开发机上没有 Xcode，编译不了 UI 测试，所以这类改名只能到 CI 才发现。
    ///
    ///   这里自己轮询 `state`，不依赖任何版本的 API 名字 ——
    ///   反正要等的东西很简单，没必要为了少写三行去接一个会改名的接口。
    private func waitUntilForeground(_ app: XCUIApplication,
                                     timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if app.state == .runningForeground { return true }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return app.state == .runningForeground
    }

    /// 轮询等待文件出现。比固定 sleep 稳，也比固定 sleep 快。
    private func waitForFile(_ url: URL, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if FileManager.default.fileExists(atPath: url.path) {
                // 文件可能正在被写，等一下确保写完
                if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                   let size = attrs[.size] as? Int, size > 100 {
                    return true
                }
            }
            Thread.sleep(forTimeInterval: 0.3)
        }
        return false
    }
}
