import UIKit

/// App 入口。
///
/// 用 `AppDelegate` + `SceneDelegate`（而不是 SwiftUI 的 `@main App`）的原因：
///
/// 1. **部署目标要压到 iOS 15**。用户的设备是 iPhone 8 Plus / iOS 16.7.14，
///    但部署目标定低一点没坏处；SwiftUI 的部分 API 在这个跨度上差异较大，
///    用 UIKit 更可控。
///
/// 2. **界面全部用代码写，不用 Storyboard / XIB**。这与安卓版的做法一致
///    （那边也是纯代码构建视图，没有 XML 布局）。好处是布局逻辑可读、
///    可 diff、可以在代码里加诊断；坏处是没法用 Xcode 的界面预览 ——
///    但开发机是 Windows，本来就没有预览可用，所以没有损失。
///
/// 3. **Swift 6 的并发检查**。AppDelegate 的方法都在主线程调用，
///    加 `@MainActor` 才能通过严格并发检查，同时语义上也更准确。
@main
@MainActor
final class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        // 全局崩溃捕获：把崩溃写进文档目录，用户能从「文件」App 导出来
        NSSetUncaughtExceptionHandler { exception in
            let text = """
            异常: \(exception.name.rawValue)
            原因: \(exception.reason ?? "无")
            堆栈:
            \(exception.callStackSymbols.joined(separator: "\n"))
            """
            Diag.recordCrash(text)
        }

        // 记一条启动痕迹。诊断文件是每次布局后覆盖的，
        // 如果 App 启动就崩（还没走到布局），至少这里能留下线索。
        Diag.recordCrash("App 启动 (iOS \(UIDevice.current.systemVersion), \(Diag.deviceModel()))")

        return true
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default Configuration",
                                          sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    func application(_ application: UIApplication,
                     didDiscardSceneSessions sceneSessions: Set<UISceneSession>) {
    }
}
