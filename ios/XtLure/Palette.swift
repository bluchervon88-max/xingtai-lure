import UIKit

/// 配色。
///
/// ## 为什么叫 Palette 而不是 UI
///
/// 安卓版里这个类叫 `Ui`，移植时本来想保持一致。但 Swift 里 `UI` 会和 UIKit 的
/// 类型前缀撞车 —— `UIView`、`UIColor` 这些都是 `UI` + 名字的形式，
/// 定义一个叫 `UI` 的类型虽然语法上允许，但读起来极易误解，
/// 而且万一将来有人写 `UI.something` 会分不清是配色还是 UIKit。
/// 换成 `Palette` 语义更准（它就是一堆颜色）。
///
/// ## 颜色值与安卓版严格一致
///
/// 深色卡片的几个值在安卓那边是**校准过的**，不要随手改：
///
/// 深色模式不能用阴影表达层级（阴影在深底上不可见），唯一手段是让每一层
/// 比下一层更亮一点。Material Design 3 对最低一层的要求是相对背景 ≥ +5%。
///
/// 早期版本的卡片色 `#171C24` 相对背景只有 +4.51%，压线不足 ——
/// 结果是 73 张卡片看起来像贴在同一个大黑底上，用户反馈"单调"。
/// 调成 `#1B2230`（+7.65%）之后层次才出来。
///
/// ```
/// 角色        值          相对背景
/// bg         #0E1116     —
/// card       #1B2230     +7.65%
/// card2      #242C3A     +11.37%
/// border     #3A4557     +21.37%
/// ```
///
/// 这些数字由 `testdata/vectors.json` 的 `theme` 一节锁定，
/// Java 和 Swift 两侧的测试都会校验对比度，改坏了会被测出来。
enum Palette {

    // MARK: - 从 vectors.json 的 theme 一节同步的值
    //
    // 契约文件里记的是 bg / card / text / accent 四个色值加对比度下限。
    // 这里多出来的 surface2 / surface3 / border 是安卓版有、契约没覆盖的，
    // 保留是为了两版观感一致。

    static let bg = UIColor(hex: 0x0E1116)
    static let card = UIColor(hex: 0x1B2230)
    static let card2 = UIColor(hex: 0x242C3A)
    static let card3 = UIColor(hex: 0x2C3545)
    static let border = UIColor(hex: 0x3A4557)

    static let text = UIColor(hex: 0xE9EEF5)
    static let textDim = UIColor(hex: 0x9AA6B5)
    static let textFaint = UIColor(hex: 0x6B7684)

    static let accent = UIColor(hex: 0x2FB6A8)
    static let accentDark = UIColor(hex: 0x1E8C81)

    static let warn = UIColor(hex: 0xE8A33D)
    static let danger = UIColor(hex: 0xE05A5A)
    static let ok = UIColor(hex: 0x4FBF6B)
    static let fav = UIColor(hex: 0xF0B429)

    // MARK: - 尺寸常量（与安卓版对齐，单位是 pt 而不是 dp —— iOS 的 pt 概念等价于 dp）

    static let radiusCard: CGFloat = 12
    static let radiusChip: CGFloat = 99
    static let radiusButton: CGFloat = 10

    /// 触摸目标下限。Apple 的人机界面指南要求 44pt，安卓是 48dp。
    /// 取较大的一方：在 iPhone 上按 44pt 是底线，再小就点不准了。
    static let touchMin: CGFloat = 44
}

extension UIColor {

    /// 用 0xRRGGBB 形式的整数构造颜色。
    ///
    /// Swift 没有从十六进制字面量建颜色的内置写法，而这个项目里
    /// 配色值全是从安卓版照搬的十六进制，用这个构造器能保持两边写法一致、
    /// 便于逐一对照。
    convenience init(hex: UInt32) {
        let r = CGFloat((hex >> 16) & 0xFF) / 255.0
        let g = CGFloat((hex >> 8) & 0xFF) / 255.0
        let b = CGFloat(hex & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: 1.0)
    }
}
