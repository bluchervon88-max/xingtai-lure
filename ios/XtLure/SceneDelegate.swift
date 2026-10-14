import UIKit

/// 场景代理：创建窗口和根视图控制器。
///
/// iOS 13 起，App 的窗口由 `UIScene` 管理而不是 `AppDelegate`。
/// 这里是最小实现 —— 建窗口、挂根控制器、显示。
@MainActor
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {

        guard let windowScene = scene as? UIWindowScene else {
            Diag.recordCrash("致命: scene 不是 UIWindowScene，无法创建窗口")
            return
        }

        let window = UIWindow(windowScene: windowScene)
        // 深色主题：与安卓版一致（夜钓场景不刺眼），也在 Info.plist 里锁了 Dark
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = MainViewController()
        window.makeKeyAndVisible()
        self.window = window

        // 界面挂上去之后跑一次布局诊断。
        // 这是开发者"看见"这台 iPhone 的唯一途径（详见 Diagnostics.swift）。
        if let root = window.rootViewController?.view {
            Diag.attach(to: root, label: "启动首屏")
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
    }

    func sceneWillResignActive(_ scene: UIScene) {
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
    }
}
