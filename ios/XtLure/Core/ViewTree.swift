// ★ 本文件是生成物，请勿直接修改。
//
// 单一来源: ios/Sources/XtLureCore/ViewTree.swift
// 同步方式: node ios/sync-sources.js
//
// 为什么要有这份副本：Xcode 工程按固定相对路径找文件，而逻辑层的单一来源
// 放在 XtLureCore 是为了能在 Windows 上用 swiftc 直接编译测试。
// 直接改这份副本会在下次同步时被覆盖 —— 请改单一来源。
import Foundation

/// 界面诊断的**纯逻辑**部分：视图树的数据描述、格式化成报告、找出问题视图。
///
/// ## 为什么要从这个文件里拆出来
///
/// `Diagnostics.swift` 是整个 iOS 路线里**唯一能让开发者"看见"设备的东西** ——
/// Windows 上没有 Xcode、没有 Safari 远程调试、连设备都看不到，
/// 界面长什么样、控件多大、哪个控件不可见，全靠它的输出。
///
/// 但正因为它依赖 UIKit，它在开发机上**一行都跑不了**：
/// 只能等到装到真机上按一下按钮才知道对不对。
/// 万一它自己崩了，就彻底失去了唯一的观测手段 —— 而这时连"哪里错了"都无从下手。
///
/// 所以把与 UIKit 无关的部分拆到这里：
///   · `ViewNode`        —— 视图树的纯数据描述（不持有 UIView）
///   · `makeReport`      —— 格式化成文本报告
///   · `findZeroSized`   —— 找出"有内容但尺寸为 0"的控件
///   · `modelName`       —— 机型标识转通俗名称
///
/// 这些能在 Linux CI 上直接验证，也有本地测试兜着。
/// `Diagnostics.swift` 只剩"把 UIView 拍扁成 ViewNode"和"写文件"两件杂事。
///
/// ## 这个模块要解决的核心问题
///
/// 安卓版有几个 bug 是靠 `uiautomator dump` 才定位的。最典型的一个：
/// 「记录作钓」表单里 **10 个输入框全部不可见** —— 代码看着没问题、编译也过了，
/// 但 dump 出来是 `EditText 宽 931px 高 0px`，直接指向了布局参数的写法错误。
///
/// **iOS 上没有这套工具，所以必须让 App 自己把同样的信息写出来。**
/// 其中最有价值的一条就是「有内容但尺寸为 0 的控件」——
/// 控件存在、代码没报错、但用户看不见，这是最难自查的一类缺陷。
public enum ViewTree {

    /// 视图树的纯数据描述。
    ///
    /// 刻意不持有 `UIView`：这样报告生成逻辑就与 UIKit 无关，
    /// 可以喂手造的树来测试。真机上则由 `Diagnostics` 把 UIView 拍扁成这个结构。
    public struct ViewNode {
        /// 类型名，例如 `UILabel`、`UIStackView`
        public var typeName: String
        /// frame（相对父视图）
        public var x: Double
        public var y: Double
        public var width: Double
        public var height: Double
        /// 是否隐藏（isHidden）
        public var hidden: Bool
        /// 是否几乎全透明（alpha < 0.05）
        public var transparent: Bool
        /// 滚动视图的内容高度；非滚动视图为 nil
        public var scrollContentHeight: Double?
        /// 文字内容（label 的 text / button 的 title / textfield 的 placeholder）
        public var text: String?
        /// 是否属于"有内容"的控件类型（决定要不要参与零尺寸检查）
        public var isContentBearing: Bool
        public var children: [ViewNode]

        public init(typeName: String, x: Double, y: Double,
                    width: Double, height: Double,
                    hidden: Bool = false, transparent: Bool = false,
                    scrollContentHeight: Double? = nil, text: String? = nil,
                    isContentBearing: Bool = false, children: [ViewNode] = []) {
            self.typeName = typeName
            self.x = x
            self.y = y
            self.width = width
            self.height = height
            self.hidden = hidden
            self.transparent = transparent
            self.scrollContentHeight = scrollContentHeight
            self.text = text
            self.isContentBearing = isContentBearing
            self.children = children
        }
    }

    /// 遍历限制：超过就截断并标注。
    ///
    /// 73 张钓点卡片会让节点数上千，报告太大会卡主线程、也会淹没关键信息。
    /// 截断时明确写出"已截断" —— 静默截断会让人以为界面就这么多内容。
    public static let maxDepth = 40
    public static let maxNodes = 4000

    /// 尺寸小于这个值就认为"不可见"。
    /// 用 0.5 而不是 0：亚像素尺寸（0.3pt）在实际渲染中也是看不见的。
    public static let zeroThreshold = 0.5

    // MARK: - 报告生成

    /// 生成完整的诊断报告文本。
    ///
    /// - Parameters:
    ///   - node: 根视图
    ///   - label: 这次诊断的标记（例如"启动首屏"）
    ///   - deviceModel: 机型标识，例如 `iPhone10,2`
    ///   - systemVersion: 系统版本，例如 `16.7.14`
    ///   - screenWidth/Height: 屏幕尺寸（pt）
    ///   - screenScale: 屏幕倍率
    ///   - safeArea: 安全区 (上, 左, 下, 右)
    ///   - timestamp: 时间戳文本
    public static func makeReport(node: ViewNode,
                                  label: String,
                                  deviceModel: String,
                                  systemVersion: String,
                                  screenWidth: Double,
                                  screenHeight: Double,
                                  screenScale: Double,
                                  safeArea: (top: Double, left: Double,
                                             bottom: Double, right: Double),
                                  timestamp: String) -> String {
        var out: [String] = []

        out.append("==== 布局实测 \(timestamp) ====")
        out.append("标记: \(label)")
        out.append("设备: \(deviceModel) (\(modelName(deviceModel))) / iOS \(systemVersion)")
        out.append(String(format: "屏幕: %.1f x %.1f pt  (scale %.1f, 原生 %.0fx%.0f)",
                          screenWidth, screenHeight, screenScale,
                          screenWidth * screenScale, screenHeight * screenScale))
        out.append(String(format: "安全区: top=%.1f left=%.1f bottom=%.1f right=%.1f",
                          safeArea.top, safeArea.left, safeArea.bottom, safeArea.right))

        // 安全区底部的提示针对用户的机型（有 Home 键，正常应为 0）。
        // 写出来是为了让"跑错机型"或"读取时机过早"这两种情况一眼可见。
        if safeArea.bottom > 1 {
            out.append("  ⚠ 底部安全区非 0。iPhone 8 Plus 有 Home 键、没有手势条，")
            out.append("    正常情况下这里应该是 0。非 0 说明跑在了别的机型上，")
            out.append("    或者读取 safeAreaInsets 的时机太早（还没完成布局）。")
        }

        out.append("")
        out.append("---- 视图层级 ----")

        var flat: [(depth: Int, node: ViewNode)] = []
        var truncated = false
        walk(node, depth: 0, into: &flat, truncated: &truncated)

        for (depth, n) in flat {
            out.append(line(for: n, depth: depth))
        }
        if truncated {
            out.append("… 已截断（深度超过 \(maxDepth) 或节点超过 \(maxNodes)）")
        }
        out.append("---- 共 \(flat.count) 个节点 ----")

        // 最有价值的一段放在后面但醒目：这些控件用户看不见，代码却不报错
        let zero = findZeroSized(node)
        out.append("")
        if zero.isEmpty {
            out.append("✅ 未发现零尺寸的可见控件")
        } else {
            out.append("⚠ 发现 \(zero.count) 个「有内容但尺寸为 0」的控件（用户看不见它们）：")
            for (depth, n) in zero.prefix(30) {
                out.append("   " + line(for: n, depth: depth))
            }
            if zero.count > 30 {
                out.append("   … 还有 \(zero.count - 30) 个")
            }
            out.append("")
            out.append("  这类控件的特征是：代码里存在、编译通过、没有报错，")
            out.append("  但因为宽或高是 0，在屏幕上看不到。")
            out.append("  安卓版踩过同样的坑（记录表单 10 个输入框全部不可见），")
            out.append("  根因是横向容器里把高度也传了 0 —— 权重只分配主轴尺寸。")
        }

        out.append("")
        return out.joined(separator: "\n")
    }

    /// 广度无关的深度优先遍历，带上深度信息
    private static func walk(_ node: ViewNode, depth: Int,
                             into out: inout [(depth: Int, node: ViewNode)],
                             truncated: inout Bool) {
        if depth > maxDepth || out.count >= maxNodes {
            truncated = true
            return
        }
        out.append((depth, node))
        for c in node.children {
            walk(c, depth: depth + 1, into: &out, truncated: &truncated)
        }
    }

    /// 把单个节点格式化成一行
    public static func line(for n: ViewNode, depth: Int) -> String {
        var s = String(repeating: "  ", count: depth)
        s += n.typeName + " "
        s += String(format: "%.1fx%.1f @ (%.1f, %.1f)", n.width, n.height, n.x, n.y)

        if n.hidden {
            s += " [隐藏]"
        }
        if n.transparent {
            s += " [透明]"
        }
        if let ch = n.scrollContentHeight {
            s += " [可滚动 内容高 \(Int(ch))]"
        }
        if let t = n.text, !t.isEmpty {
            s += " '\(t.prefix(40))'"
        }
        return s
    }

    /// 找出「有内容、可见、但宽或高为 0」的控件。
    ///
    /// 判定条件刻意收紧，避免误报：
    ///   · 必须是"有内容"的类型（label / button / textfield / imageview）
    ///   · 必须没有 isHidden（隐藏是**有意**的，不算缺陷）
    ///   · 宽**或**高小于阈值即算（只挑一个方向也算看不见）
    public static func findZeroSized(_ root: ViewNode) -> [(depth: Int, node: ViewNode)] {
        var out: [(depth: Int, node: ViewNode)] = []
        collectZero(root, depth: 0, into: &out)
        return out
    }

    private static func collectZero(_ n: ViewNode, depth: Int,
                                    into out: inout [(depth: Int, node: ViewNode)]) {
        if n.isContentBearing && !n.hidden
            && (n.width < zeroThreshold || n.height < zeroThreshold) {
            out.append((depth, n))
        }
        for c in n.children {
            collectZero(c, depth: depth + 1, into: &out)
        }
    }

    // MARK: - 机型识别

    /// 机型标识 -> 通俗名称。
    ///
    /// 为什么值得单独维护这张表：机型决定**屏幕比例与安全区**，
    /// 而这两样是布局的前提。iPhone 8 Plus 是 16:9、无刘海、底部安全区为 0；
    /// 新机型是 19.5:9 且有刘海。排查布局问题时，"到底跑在哪台设备上"是第一个要确认的事。
    ///
    /// 表里只列与本项目相关的常见机型，查不到的返回"未知机型" ——
    /// 未知时仍然会输出原始标识（如 `iPhone14,7`），不会丢失信息。
    public static func modelName(_ identifier: String) -> String {
        let map: [String: String] = [
            // 有 Home 键（16:9，底部安全区为 0）
            "iPhone10,1": "iPhone 8",
            "iPhone10,2": "iPhone 8 Plus",
            "iPhone10,4": "iPhone 8",
            "iPhone10,5": "iPhone 8 Plus",
            "iPhone9,1": "iPhone 7",
            "iPhone9,3": "iPhone 7",
            "iPhone9,2": "iPhone 7 Plus",
            "iPhone9,4": "iPhone 7 Plus",
            // 刘海屏（19.5:9，底部安全区 34）
            "iPhone11,2": "iPhone XS",
            "iPhone11,4": "iPhone XS Max",
            "iPhone11,6": "iPhone XS Max",
            "iPhone11,8": "iPhone XR",
            "iPhone12,1": "iPhone 11",
            "iPhone12,3": "iPhone 11 Pro",
            "iPhone12,5": "iPhone 11 Pro Max",
            "iPhone13,1": "iPhone 12 mini",
            "iPhone13,2": "iPhone 12",
            "iPhone13,3": "iPhone 12 Pro",
            "iPhone13,4": "iPhone 12 Pro Max",
            "iPhone14,7": "iPhone 14",
            "iPhone14,8": "iPhone 14 Plus",
            "iPhone15,4": "iPhone 15",
            "iPhone15,5": "iPhone 15 Plus",
        ]
        return map[identifier] ?? "未知机型"
    }
}
