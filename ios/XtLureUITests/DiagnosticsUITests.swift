import XCTest

/// 诊断功能的端到端验证 —— **界面部分**。
///
/// ## 为什么需要这个测试
///
/// 「诊断能不能用」这件事在开发机上**完全验证不了** —— Windows 上没有 UIKit。
/// 而它恰恰是整条 iOS 路线里最关键的一环：
///
/// > 开发机是 Windows，看不到 iPhone 屏幕。
/// > 诊断模块是**唯一**能把"界面实际长什么样"带回来的通道。
///
/// 如果它有问题，用户在真机上按了按钮也拿不到东西，
/// 而开发者只能干等一轮（一次真机往返 = 用户手动装 IPA + 操作 + 发文件）。
///
/// 所以这个测试在 CI 的 iOS 模拟器上**真正启动 App**，验证：
///
///   1. App 能启动且不崩溃          —— 崩溃的话 `launch()` 就失败
///   2. 首屏的关键内容真的在屏幕上   —— 不只是"创建了控件"，而是能被无障碍接口看到
///   3. 点「诊断」按钮不崩           —— 用户要点的就是它
///
/// ## 关于「诊断文件到底写出来没有」—— 分工说明
///
/// 这个测试**不读文件**。试过两条路，都不通：
///
///   1. `FileManager.default.urls(for: .documentDirectory, ...)`
///      → 拿到的是**测试进程自己**的目录（UI 测试跑在独立沙箱里），
///        于是报"文件没写出来"、目录内容为「（空）」，
///        看起来像 App 没写文件，实际是读错了地方。
///        **这类失败会把人引向完全错误的方向** —— 比失败本身更糟。
///
///   2. `XCUIApplication.containerURL`
///      → Xcode 16.4 报 `has no member 'containerURL'`，这个接口在这个版本不存在。
///
/// 所以职责分开：
///   · **本测试**：启动、点按、断言界面元素（不需要任何文件路径）
///   · **CI 里另一步**（workflow 的「核验诊断文件」）：
///     用 `simctl get_app_container` 从**宿主机侧**确认文件存在
///
/// 后者的视角其实更接近真实场景 —— 用户在真机上也是从"外面"（「文件」App）
/// 把文件拿出来的，而不是从进程内部读。
final class DiagnosticsUITests: XCTestCase {

    /// 被测 App。目标名与 scheme 里的 BlueprintName 一致。
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 核心测试

    /// App 能启动，首屏内容正确。
    func testAppLaunchesWithExpectedFirstScreen() throws {
        app = XCUIApplication()
        app.launch()

        XCTAssertTrue(waitUntilForeground(app, timeout: 30),
                      "App 没有进入前台 —— 可能启动即崩溃")

        // 等界面稳定
        Thread.sleep(forTimeInterval: 2.0)

        // ---- 首屏的关键内容必须真的在屏幕上
        //
        // ★ 这里查的是**无障碍接口**能看到的文字，而不是"代码里创建了 UILabel"。
        //   两者差别很大：控件创建了但尺寸为 0、或没加进视图树、或被遮挡，
        //   在无障碍接口里就看不到 —— 那正是安卓版踩过的坑
        //   （记录表单 10 个输入框全部不可见，代码却"看着没问题"）。
        for section in ["运行环境", "预置数据", "算法自检"] {
            XCTAssertTrue(waitForStaticText(containing: section, timeout: 15),
                          "首屏看不到「\(section)」这一节 —— "
                          + "可能界面没建起来，或者控件尺寸为 0 被判定为不可见")
        }

        // ---- 算法自检的条目必须有输出
        //
        // 这些数字在电脑上已经过了 133 项契约测试，但那是 Windows 的 x86_64 运行时。
        // 真机和模拟器是 arm64，浮点结果必须仍然一致 ——
        // 尤其天文计算那种大量三角函数的。
        //
        // 这里只断言"这些条目有输出"，具体数值的核对交给契约测试
        // （模拟器与真机仍可能有细微差别，写死数值会让测试变脆）。
        for row in ["坐标偏移量", "往返误差", "夏至日出", "今日月龄", "投影比例"] {
            XCTAssertTrue(waitForStaticText(containing: row, timeout: 8),
                          "算法自检里看不到「\(row)」这一项")
        }

        // ---- 屏幕比例：判断"跑在什么设备上"的第一依据
        XCTAssertTrue(waitForStaticText(containing: "屏幕比例", timeout: 8),
                      "看不到「屏幕比例」这一项 —— 它是判断机型的第一个依据")

        // ---- 把界面上的文字全打出来
        //
        // 这是这个测试最有价值的副产品：**CI 日志里能看到模拟器上真实的界面内容**。
        // 在没有真机的时候，这已经是最接近"看见屏幕"的东西了。
        print("──────── 模拟器上首屏的全部可见文字 ────────")
        let all = app.staticTexts
        for i in 0..<all.count {
            let t = all.element(boundBy: i)
            if !t.label.isEmpty {
                print("  [\(i)] \(t.label)")
            }
        }
        print("──────── 共 \(all.count) 个文字元素 ────────")
    }

    /// 点「诊断」按钮不崩。
    ///
    /// 用户在真机上要点的就是它。前端崩溃意味着这条路走不通。
    func testDiagnoseButtonDoesNotCrash() throws {
        app = XCUIApplication()
        app.launch()
        XCTAssertTrue(waitUntilForeground(app, timeout: 30))

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

        // 诊断页是 push 出来的，应该能退回来
        let backButton = app.navigationBars.buttons.element(boundBy: 0)
        if backButton.exists {
            backButton.tap()
            XCTAssertTrue(waitUntilForeground(app, timeout: 10),
                          "从诊断页返回之后 App 状态异常")
        }
    }

    // MARK: - 工具

    /// 等 App 进入前台。
    ///
    /// ★ 不用 `XCUIApplication.waitForState(_:timeout:)` —— Xcode 16 起那个 API
    ///   被改名为 `wait(for:timeout:)`，用旧名会直接编译失败：
    ///
    ///     error: 'waitForState(_:timeout:)' has been renamed to 'wait(for:timeout:)'
    ///
    ///   而开发机上没有 Xcode、编译不了 UI 测试，所以这类改名只能到 CI 才发现。
    ///
    ///   自己轮询 `state` 不依赖任何版本的 API 名字 ——
    ///   要等的东西很简单，没必要为了少写三行去接一个会改名的接口。
    private func waitUntilForeground(_ app: XCUIApplication,
                                     timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if app.state == .runningForeground { return true }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return app.state == .runningForeground
    }

    /// 等某个包含指定文字的元素出现。
    ///
    /// 用 `CONTAINS` 而不是相等匹配：首屏的文字是动态拼出来的
    /// （例如"设备      iPhone10,2"中间有填充空格），精确匹配会很脆。
    private func waitForStaticText(containing needle: String,
                                   timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate(format: "label CONTAINS %@", needle)
        let element = app.staticTexts.containing(predicate).firstMatch
        return element.waitForExistence(timeout: timeout)
    }
}
