import Foundation

/// 离线矢量底图：载入与解码。
///
/// ## 为什么自己做底图，而不是接商用地图 SDK
///
/// 高德 / 百度 / 腾讯的 iOS SDK 都需要三样东西：
///   1. 申请开发者账号 + 审核 + Bundle ID 绑定的 API Key（需要本人去申请）
///   2. 网络访问权限，否则一张瓦片都下载不下来
///   3. 联网环境，而水库边经常没信号
///
/// 本 App 的核心卖点恰恰是「不联网、离线可用」，接 SDK 会把这一点推翻。
/// 需要商用地图时用「导航」功能跳转到手机上的高德/百度即可 ——
/// 由对方 App 去联网，本 App 依旧不需要**任何**网络权限。
///
/// 所以走另一条路：把真实地理数据编译进 App，本地矢量绘制。
///   · 数据来源：OpenStreetMap（ODbL 许可，可自由使用，已在关于页署名）
///   · 体积：水系 80KB + 区界 106KB + 河道 33KB ≈ 218KB，比瓦片包小两个数量级
///   · 缩放不会糊：存的是顶点，不是位图
///
/// ## 数据格式（由 tools/build-basemap.js 生成）
///
/// 表头两行：文件标识 + 图层名
/// 数据行：`类型|编码的顶点流|local|标注y|标注x|名称`
///   类型：W 水体面 / B 区县界 / R 河道线
///
/// 顶点流用 base-32 + 续位标记编码（详见 tools/basemap-codec.js）：
///   · 每位 5 位数映射到字符 `(char)('0' + d)`
///   · 除最后一位外，字符码 +0x20
///   · 解码时看字符码是否 >= 0x50 判断「后面还有」
///   · 值映射：非负 v -> 2v，负数 v -> 2|v|+1（最低位是符号位）
///   · 顶点存的是**与上一点的差值**，相邻点差值小，通常 1~2 个字符
///
/// 坐标按 1e-5 度量化（约 1 米精度），坐标系 **WGS-84**，
/// 与 App 内钓点坐标一致，绘制前不需要任何转换。
///
/// ## 关于这个文件为什么只有载入解码、没有绘制
///
/// 安卓版的 `Basemap.java` 把「载入解码」和「Canvas 绘制」放在一个类里。
/// 移植时**刻意拆开**：
///
///   · 解码是纯逻辑（数字与字符串），能在 Windows 上直接编译验证，
///     也能写契约测试锁住与 Java 版的一致性
///   · 绘制依赖 CoreGraphics，只能在 macOS 上编译，
///     而且真正难的部分是渲染效果 —— 那需要真机看
///
/// 混在一起的话，解码正确性会被绘制的不确定性掩盖：
/// 地图显示不对时，分不清是解码错了还是画错了。
/// 拆开之后，解码有 34 项契约测试兜着（见 VectorTest），
/// 剩下的问题必然出在绘制。
public struct Basemap {

    /// 坐标量化精度：1e-5 度，约 1 米
    private static let scale: Double = 1e5

    /// 编码字符的起始码位（'0'）
    private static let charBase: Int = 0x30
    /// 续位标记：字符码加上它表示「后面还有」
    private static let continuation: Int = 0x20
    /// 进制
    private static let radix: Int = 32

    // MARK: - 几何

    /// 一条几何（闭合轮廓或折线）
    public struct Shape {
        /// 纬度序列（WGS-84）
        public var lats: [Float] = []
        /// 经度序列（WGS-84）
        public var lons: [Float] = []
        /// 闭合轮廓 = true（水体面、区县界），折线 = false（河道）
        public var closed = false
        /// 水体名 / 区县名（可能为空）
        public var name = ""
        /// 是否属于邢台市（区县界用来区分本市与周边地市，周边画得更淡）
        public var local = false
        /// 是否行政区界（与水体同为闭合面，靠类型字符区分）
        public var isBoundary = false
        /// 是否渠道（河道专用）
        public var canal = false
        /// 名称标注位置
        public var labelLat: Float = 0
        public var labelLon: Float = 0
        /// 顶点数
        public var count: Int { return lats.count }
    }

    public private(set) var water: [Shape] = []
    public private(set) var boundary: [Shape] = []
    public private(set) var river: [Shape] = []
    public private(set) var vertexCount = 0
    public private(set) var loaded = false

    public init() {
    }

    // MARK: - 载入

    /// 三个底图文件的资源名（不含扩展名）。顺序与 Java 版一致。
    public static let fileNames = ["basemap_water", "basemap_river", "basemap_boundary"]

    /// 从 App 资源包里载入全部底图数据。
    ///
    /// 解析约 5.3 万个顶点。解析失败**不影响主流程**（地图退化为只有钓点），
    /// 但会把原因记录下来 —— 底图缺失时地图仍然可用，只是没有水系参照。
    public mutating func loadFromBundle(_ bundle: Bundle = .main) -> String {
        guard let waterURL = bundle.url(forResource: "basemap_water", withExtension: "txt"),
              let riverURL = bundle.url(forResource: "basemap_river", withExtension: "txt"),
              let boundaryURL = bundle.url(forResource: "basemap_boundary", withExtension: "txt")
        else {
            return "底图资源缺失（basemap_*.txt 没有被正确打包进 App）"
        }
        return load(waterURL: waterURL, riverURL: riverURL, boundaryURL: boundaryURL)
    }

    /// 从文件载入。拆出这个入口是为了让**测试**能直接喂文件路径，
    /// 不依赖 Bundle（Windows 上没有 Bundle 资源的概念）。
    public mutating func load(waterURL: URL, riverURL: URL, boundaryURL: URL) -> String {
        if loaded {
            return "已载入，跳过"
        }
        let t0 = Date()

        do {
            // 顺序与 Java 版一致（水系 -> 河道 -> 区界）。
            // 载入顺序不影响正确性，但保持一致便于两边日志对照。
            vertexCount += try Self.loadFile(waterURL, into: &water, closed: true)
            vertexCount += try Self.loadFile(riverURL, into: &river, closed: false)
            vertexCount += try Self.loadFile(boundaryURL, into: &boundary, closed: true)
        } catch {
            return "底图载入失败：\(error)。地图将只显示钓点。"
        }
        loaded = true

        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        return "底图载入完成：水系 \(water.count) 个，河道 \(river.count) 条，"
            + "区县界 \(boundary.count) 个，顶点 \(vertexCount)，耗时 \(ms)ms"
    }

    /// 载入一个文件。返回累计的顶点数。
    private static func loadFile(_ url: URL, into target: inout [Shape],
                                closed: Bool) throws -> Int {
        let text = try String(contentsOf: url, encoding: .utf8)
        var count = 0
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let lineStr = String(line)
            if lineStr.isEmpty {
                continue
            }
            // 表头行：文件标识（XTLURE…）或图层名（纯字母）
            if lineStr.hasPrefix("XTLURE") || (!lineStr.contains("|") && isAlpha(lineStr)) {
                continue
            }
            if let s = decode(lineStr), s.lats.count >= 2 {
                target.append(s)
                count += s.lats.count
            }
            // 解码失败的行静默跳过：底图是辅助图层，个别几何坏掉不该影响整体
        }
        return count
    }

    private static func isAlpha(_ s: String) -> Bool {
        if s.isEmpty {
            return false
        }
        for ch in s.unicodeScalars {
            let v = ch.value
            let isLower = v >= 0x61 && v <= 0x7A
            let isUpper = v >= 0x41 && v <= 0x5A
            if !isLower && !isUpper {
                return false
            }
        }
        return true
    }

    // MARK: - 解码

    /// 解码游标。用 class 是为了在递归/多次调用间共享位置（与 Java 版一致）。
    private final class Cursor {
        let s: [Character]
        var i = 0
        init(_ s: String) {
            self.s = Array(s)
        }
        var atEnd: Bool { return i >= s.count }
    }

    /// 读一个变长整数。
    ///
    /// 与 `tools/basemap-codec.js` 的 `decodeInt` **严格对应**：
    /// 逐字符读，字符码 >= 0x50 表示「后面还有」；值的最低位是符号位。
    ///
    /// ★ 这里曾经出过一个 off-by-one 的 bug：
    ///   编码端写的是 `2|v|+1`，解码端却写成 `-(value+1)/2`，
    ///   于是所有负数的恢复值都差 1。是 `tools/verify-codec.js` 的
    ///   往返测试抓出来的 —— 所以那个脚本必须留着，不能删。
    private static func readInt(_ c: Cursor) -> Int {
        var value = 0
        var pow = 1
        while !c.atEnd {
            let code = Int(c.s[c.i].unicodeScalars.first!.value)
            c.i += 1
            let more = code >= charBase + continuation
            let digit = code - charBase - (more ? continuation : 0)
            value += digit * pow
            pow *= radix
            if !more {
                break
            }
        }
        // 与编码端互逆：奇数表示负数 v = -(value-1)/2，偶数表示 v = value/2
        return (value & 1) == 1 ? -((value - 1) / 2) : (value / 2)
    }

    /// 解码一行。
    ///
    /// 行格式：`类型|顶点流|local|标注y|标注x|名称`
    ///
    /// 注意 `split` 的 limit=6：名称里可能有 `|`，要保留在最后一个字段里。
    /// 用 `maxSplits` 而不是「全拆再拼回去」，与 Java 的 `split("\\|", 6)` 等价。
    static func decode(_ line: String) -> Shape? {
        // limit 6 的意思是「最多切出 6 段」，等价于 Java 的 split(regex, 6)
        let parts = line.split(separator: "|", maxSplits: 5,
                               omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2 else {
            return nil
        }
        let type: Character = parts[0].isEmpty ? "?" : parts[0].first!
        let body = parts[1]

        let c = Cursor(body)
        let n = readInt(c)
        if n <= 0 || n > 20000 {
            return nil
        }

        var lats = [Float](repeating: 0, count: n)
        var lons = [Float](repeating: 0, count: n)
        var px = 0
        var py = 0
        for i in 0..<n {
            px += readInt(c)
            py += readInt(c)
            lons[i] = Float(Double(px) / scale)
            lats[i] = Float(Double(py) / scale)
        }

        var shape = Shape()
        shape.lats = lats
        shape.lons = lons
        // 河道是折线，其余闭合
        shape.closed = (type != "R")

        if parts.count >= 3 {
            shape.local = (parts[2] == "1")
        }
        if parts.count >= 4 && !parts[3].isEmpty {
            shape.labelLat = Float(Double(parseIntSafe(parts[3])) / scale)
        } else {
            shape.labelLat = lats[0]
        }
        if parts.count >= 5 && !parts[4].isEmpty {
            shape.labelLon = Float(Double(parseIntSafe(parts[4])) / scale)
        } else {
            shape.labelLon = lons[0]
        }
        if parts.count >= 6 {
            shape.name = parts[5]
        }
        // 区分水体与区县界：都可能是闭合面，靠类型字符判断
        shape.isBoundary = (type == "B")
        return shape
    }

    private static func parseIntSafe(_ s: String) -> Int {
        return Int(s.trimmingCharacters(in: .whitespaces)) ?? 0
    }

    // MARK: - 范围与剔除

    /// 底图覆盖的地理范围（minLat, minLon, maxLat, maxLon），供地图选择初始视野。
    ///
    /// 这是**纯计算**，不涉及屏幕坐标，所以能跨平台对照。
    public func bounds() -> (minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        var minLat = 90.0, maxLat = -90.0, minLon = 180.0, maxLon = -180.0
        for group in [water, river, boundary] {
            for s in group {
                for i in 0..<s.lats.count {
                    let la = Double(s.lats[i])
                    let lo = Double(s.lons[i])
                    if la < minLat { minLat = la }
                    if la > maxLat { maxLat = la }
                    if lo < minLon { minLon = lo }
                    if lo > maxLon { maxLon = lo }
                }
            }
        }
        return (minLat, minLon, maxLat, maxLon)
    }

    /// 一条几何的经纬度外接框。
    ///
    /// 安卓版是在 `cull()` 里**顺带**按屏幕坐标算的，这里改成纯经纬度 ——
    /// 因为外接框本身与投影无关，提前算好还能复用（同一几何在多帧之间不变）。
    public func lonLatBox(_ s: Shape) -> (minLat: Float, minLon: Float,
                                          maxLat: Float, maxLon: Float) {
        var minLat = Float.greatestFiniteMagnitude
        var maxLat = -Float.greatestFiniteMagnitude
        var minLon = Float.greatestFiniteMagnitude
        var maxLon = -Float.greatestFiniteMagnitude
        for i in 0..<s.lats.count {
            let la = s.lats[i]
            let lo = s.lons[i]
            if la < minLat { minLat = la }
            if la > maxLat { maxLat = la }
            if lo < minLon { minLon = lo }
            if lo > maxLon { maxLon = lo }
        }
        return (minLat, minLon, maxLat, maxLon)
    }

    /// 统计信息，界面上用来确认底图是否真的载入了
    public func describe() -> String {
        if !loaded {
            return "底图未载入"
        }
        let b = bounds()
        return String(format: "水系 %d · 河道 %d · 区县界 %d · 顶点 %d · %.2f~%.2f°N %.2f~%.2f°E",
                      water.count, river.count, boundary.count, vertexCount,
                      b.minLat, b.maxLat, b.minLon, b.maxLon)
    }
}
