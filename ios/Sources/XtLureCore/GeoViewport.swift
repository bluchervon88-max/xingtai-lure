import Foundation

/// 地图视野与投影（纯数学）。
///
/// ★ 这是 Android 版 `ui/GeoViewport.java` 的**忠实移植**。
///   这个类当初花了很大力气才修对，移植时最大的风险不是"算错"而是"算得不太一样"：
///   比例误差 1% 在截图上看不出来，但比例尺会系统性偏，用户按格数估算距离就会错。
///   所以下面所有 pxPerXxx 的推导都逐行对照 Java，不"顺手优化"。
///
/// ★ 为什么这段数学必须单独存在
///   它原来散落在 View 的字段与方法里，比例依赖「plot 尺寸 + zoom + 中心纬度」三个值，
///   而三者分别由 setData / 布局 / 绘制三个入口更新，任何一个漏更新，比例就停在错的值上——
///   真机上表现为"缩放到 32× 才勉强能用"，靠 adb 读界面值根本定位不了。
///
/// ★ 比例（本类型存在的核心理由）
///   经纬度不能按 1:1 铺进画框。邢台（北纬 37.2°）1° 纬度约 111 km，
///   1° 经度只有 111×cos(37.2°) ≈ 88.6 km —— 相差 1.256 倍。
///   若两个方向各自拉满画框，等于把地图**横向拉伸 1.256 倍**，形状是歪的，比例尺必然错。
///   所以这里保证：`pxPerLonDeg == pxPerLatDeg × cos(centerLat)`，
///   即两个方向的**地面米数对应相同像素数**，比例尺只按纬度换算就准确。
///
/// ★ 单位约定（写错会让结论完全失真，本文件内统一）
///   所有 pxPerXxx 都是「每 **度**」的像素数，不是每 0.01°。
///   （Android 的 SpotMapView 早期版本按 0.01° 算过，两套单位混用是最初比例错乱的根因。）
///
/// ★ Java → Swift 的语义差异（已逐处处理，均为等价改写）
///   1. Java 的 `float` 是 IEEE-754 单精度，Swift 的 `Float` 同义；宽度/高度/缩放/像素
///      结果继续用 `Float` 保存，中间量用 `Double` 再一次性转换 —— 与 Java 的
///      `(float) (double 表达式)` 逐位对应。若擅自把字段改成 `Double`，
///      像素值会与 Java 差最后几位，聚类取整就可能落在格子边界的不同侧。
///   2. `Math.toRadians(x)` = `x * Double.pi / 180.0`，写法不同、结果逐位相同。
///   3. `Math.max(1f, width)` 在 Java 里 1f 会被提升成 float 运算；Swift 用 `max(1, w)`
///      对 Float 字面量同样成立。
///   4. `String.format(Locale.CHINA, "%.2f~%.2f°N %.2f~%.2f°E", ...)` 与 Swift
///      `String(format:locale:...)` 同为 printf 语义（HALF_UP），输出一致。
public struct GeoViewport {

    // MARK: - 常量

    /// 每度纬度的米数。与 Java 版一致，不要换成更精确的椭球值 ——
    /// 它同时参与 pxPerLatDeg 的推导，改了两边比例都会变。
    public static let metersPerDegLat: Double = 111320.0

    /// 1× 时东西方向的可见宽度（km）
    public static let targetKmAt1x: Double = 42.0

    public static let minZoom: Float = 0.25
    public static let maxZoom: Float = 32

    // MARK: - 视口状态

    /// 视口宽度（像素），至少 1
    public let width: Float
    /// 视口高度（像素），至少 1
    public let height: Float
    /// 缩放倍数，1× = 东西约 `targetKmAt1x` km
    public let zoom: Float
    /// 视野中心的纬度
    public let centerLat: Double
    /// 视野中心的经度
    public let centerLon: Double

    /// 构造时就把不合法的尺寸/缩放夹到有效范围。
    ///
    /// Java 版构造器里做的钳制照搬到这里：宽高至少 1px（否则后面除零），
    /// zoom 夹在 [0.25, 32]（越界会让视野宽度算成 0 或几百公里）。
    public init(width: Float, height: Float, zoom: Float,
                centerLat: Double, centerLon: Double) {
        self.width = max(1, width)
        self.height = max(1, height)
        self.zoom = max(GeoViewport.minZoom, min(GeoViewport.maxZoom, zoom))
        self.centerLat = centerLat
        self.centerLon = centerLon
    }

    // MARK: - 派生视野

    public func withZoom(_ z: Float) -> GeoViewport {
        return GeoViewport(width: width, height: height, zoom: z,
                           centerLat: centerLat, centerLon: centerLon)
    }

    public func withCenter(_ lat: Double, _ lon: Double) -> GeoViewport {
        return GeoViewport(width: width, height: height, zoom: zoom,
                           centerLat: lat, centerLon: lon)
    }

    /// 中心纬度处的经度收缩系数
    public var cosLat: Double {
        // Java: Math.cos(Math.toRadians(centerLat))，写法不同、结果相同
        return cos(centerLat * Double.pi / 180.0)
    }

    // MARK: - 比例

    /// 每度纬度对应的像素数。
    ///
    /// 推导：让 1× 时东西方向正好是 `targetKmAt1x` 公里。
    ///   目标经度跨度(度) = TARGET_KM / (111.32 × cosLat)
    ///   该跨度要铺满画框宽      => pxPerLonDeg = width / 目标经度跨度
    ///   再由 pxPerLatDeg = pxPerLonDeg / cosLat 得到
    /// 合起来：pxPerLatDeg = width / (目标经度跨度 × cosLat)
    ///
    /// 注意 111.32 是**硬编码**在公式里的（不是 metersPerDegLat 那个 111320.0/1000）：
    /// Java 版就是这么写的，两者数值相同但走的是不同常量，移植时原样保留以免将来
    /// 有人只改一个导致比例缓慢漂移。
    public var pxPerLatDeg: Float {
        let targetLonDeg = GeoViewport.targetKmAt1x / (111.32 * cosLat)
        return Float(Double(width) / (targetLonDeg * cosLat) * Double(zoom))
    }

    /// 每度经度对应的像素数（含 cos 修正，保证形状不变形）
    public var pxPerLonDeg: Float {
        return Float(Double(pxPerLatDeg) * cosLat)
    }

    /// 每像素对应多少米（两个方向相同，这是"不变形"的直接体现）
    public var metersPerPixel: Double {
        return GeoViewport.metersPerDegLat / Double(pxPerLatDeg)
    }

    // MARK: - 可见范围

    public var minLat: Double {
        return centerLat - Double(height) / 2.0 / Double(pxPerLatDeg)
    }

    public var maxLat: Double {
        return centerLat + Double(height) / 2.0 / Double(pxPerLatDeg)
    }

    public var minLon: Double {
        return centerLon - Double(width) / 2.0 / Double(pxPerLonDeg)
    }

    public var maxLon: Double {
        return centerLon + Double(width) / 2.0 / Double(pxPerLonDeg)
    }

    /// 可见的纬度跨度（度）
    public var visibleLatSpan: Double {
        return Double(height) / Double(pxPerLatDeg)
    }

    /// 可见的经度跨度（度）
    public var visibleLonSpan: Double {
        return Double(width) / Double(pxPerLonDeg)
    }

    // MARK: - 投影

    /// 经度 -> 屏幕 x
    public func x(_ lon: Double) -> Float {
        return Float(Double(width) / 2.0 + (lon - centerLon) * Double(pxPerLonDeg))
    }

    /// 纬度 -> 屏幕 y（北在上，所以纬度越大 y 越小）
    public func y(_ lat: Double) -> Float {
        return Float(Double(height) / 2.0 - (lat - centerLat) * Double(pxPerLatDeg))
    }

    /// 屏幕 x -> 经度
    public func lonAt(_ screenX: Float) -> Double {
        return centerLon + (Double(screenX) - Double(width) / 2.0) / Double(pxPerLonDeg)
    }

    /// 屏幕 y -> 纬度
    public func latAt(_ screenY: Float) -> Double {
        return centerLat - (Double(screenY) - Double(height) / 2.0) / Double(pxPerLatDeg)
    }

    // MARK: - 视野调整

    /// 以某个屏幕点为锚点缩放，返回新的视野。
    ///
    /// 锚点不动的含义：该屏幕点对应的经纬度在缩放前后保持在同一屏幕位置。
    /// 这是捏合缩放"捏哪里放大哪里"的实现基础。
    public func zoomAround(_ newZoom: Float, anchorX: Float, anchorY: Float) -> GeoViewport {
        let anchorLon = lonAt(anchorX)
        let anchorLat = latAt(anchorY)
        let scaled = withZoom(newZoom)
        let newCenterLon = anchorLon
            - (Double(anchorX) - Double(scaled.width) / 2.0) / Double(scaled.pxPerLonDeg)
        let newCenterLat = anchorLat
            + (Double(anchorY) - Double(scaled.height) / 2.0) / Double(scaled.pxPerLatDeg)
        return scaled.withCenter(newCenterLat, newCenterLon)
    }

    /// 把中心限制在给定经纬范围内。
    ///
    /// 可见范围大于给定范围时，中心直接取范围中点 ——
    /// 这样缩到最小时图不会偏在一角，也不会拖出一片空白。
    public func clampCenter(bMinLat: Double, bMinLon: Double,
                            bMaxLat: Double, bMaxLon: Double) -> GeoViewport {
        let halfLat = visibleLatSpan / 2.0
        let halfLon = visibleLonSpan / 2.0

        let lat: Double
        if halfLat * 2 >= bMaxLat - bMinLat {
            lat = (bMinLat + bMaxLat) / 2
        } else {
            lat = max(bMinLat + halfLat, min(bMaxLat - halfLat, centerLat))
        }

        let lon: Double
        if halfLon * 2 >= bMaxLon - bMinLon {
            lon = (bMinLon + bMaxLon) / 2
        } else {
            lon = max(bMinLon + halfLon, min(bMaxLon - halfLon, centerLon))
        }
        return withCenter(lat, lon)
    }

    /// 生成一个以给定经纬范围为中心、1× 缩放的视野
    public static func centeredOn(width: Float, height: Float,
                                  minLat: Double, minLon: Double,
                                  maxLat: Double, maxLon: Double) -> GeoViewport {
        return GeoViewport(width: width, height: height, zoom: 1,
                           centerLat: (minLat + maxLat) / 2,
                           centerLon: (minLon + maxLon) / 2)
    }

    /// 视野摘要，界面上用于把"比例对不对"变成可读数字
    public func describe() -> String {
        // 与 Java 的 String.format(Locale.CHINA, ...) 一致：printf 语义、两位小数。
        // ★ 不要写成 `String(format:locale:)` —— 带 locale 的重载会加千位分隔符
        //   （经度跨度大到几千度时会出现 "1,234.57"），而 Java 的 %.2f 不加。
        return String(format: "%.2f~%.2f°N %.2f~%.2f°E", minLat, maxLat, minLon, maxLon)
    }
}
