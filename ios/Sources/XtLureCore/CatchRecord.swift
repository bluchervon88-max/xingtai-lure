import Foundation

/// 一条作钓/渔获记录。
///
/// ★ 这是 Android 版 `model/CatchRecord.java` 的忠实移植。
///   与 `FishingSpot` 同样把 Java 的"null 兜底 getter"改成非可选字段
///   （`getCaughtFish()` 里写的是 `caughtFish == null ? "" : caughtFish`），
///   所以这里字段一律 `var x: String = ""`，不引入 `Optional`。
///
/// ★ 序列化用 `Codable`。`CodingKeys` 的 rawValue 与 Java 版 `Exporter.appendCatch`
///   写出的 key 完全同名（`id` / `spotId` / `spotName` / `time` / `targetFish` /
///   `caughtFish` / `count` / `sizeNote` / `lureUsed` / `method` / `weather` / `wind` /
///   `pressure` / `airTemp` / `waterTemp` / `waterNote` / `note` / `rating`），
///   所以这个枚举不需要任何改名映射。
///   Java 那边的 `timeText` 是**导出时现算**的展示字段，模型里不存、这里也不编码
///   （见下面差异说明 2）。
///
/// ★ 与 Java 的**行为差异**（必须明确）：
///   1. 导出字段集：`Exporter.swift` 与 Java 版写的是同一批 key，
///      但**额外写入 `lat` / `lon` / `hasCoord` / `createdAt`**。
///      Java 的 `Exporter.appendCatch` 忘了写坐标，导致"带坐标的作钓记录"导出后
///      坐标丢失、Android 也导不回来（它的 fromMap 里没有对应字段）。
///      这里是**有意的补齐**：只增 key、不改已有 key，Android 读多出来的 key 会忽略。
///   2. `timeText`：Java 版在导出时临时用 `SimpleDateFormat("yyyy-MM-dd HH:mm:ss")` 现算，
///      模型里不存这个字段。Swift 同样在导出时现算（`Exporter.iso`），
///      所以**不要**给 CatchRecord 加 timeText 属性去缓存它 —— 时区一变就会不一致。
///   3. 解码容错度同 FishingSpot：缺字段走默认值；类型不符抛 `DecodingError` 而不是
///      像 Java 的 `Json.num/bool` 那样强行转换。
public struct CatchRecord: Codable, Equatable, Hashable, Identifiable, Sendable {

    public var id: Int64 = 0
    /// 关联钓点，-1 表示未关联
    public var spotId: Int64 = -1
    /// 冗余存名字，防止钓点被删后记录失去可读性
    public var spotName: String = ""
    /// 作钓时间戳（毫秒，Unix epoch）
    public var time: Int64 = 0
    /// 目标鱼
    public var targetFish: String = ""
    /// 实际钓获（空 = 空军）
    public var caughtFish: String = ""
    /// 尾数
    public var count: Int = 0
    /// 体长/重量描述
    public var sizeNote: String = ""
    /// 用饵
    public var lureUsed: String = ""
    /// 钓法
    public var method: String = ""
    public var lat: Double = 0
    public var lon: Double = 0
    public var hasCoord: Bool = false
    /// 天气（手填）
    public var weather: String = ""
    /// 风向风力（手填）
    public var wind: String = ""
    /// 水情：涨水/落水/浑浊/清澈
    public var waterNote: String = ""
    /// 气压，例如 1008 或 1008hPa
    public var pressure: String = ""
    /// 气温
    public var airTemp: String = ""
    /// 水温
    public var waterTemp: String = ""
    public var note: String = ""
    /// 本次体验 1-5
    public var rating: Int = 0
    public var createdAt: Int64 = 0

    /// 字段顺序 = 导出 JSON 的 key 顺序，与 Java 版 `Exporter.appendCatch` 一致。
    public enum CodingKeys: String, CodingKey, CaseIterable {
        case id
        case spotId
        case spotName
        case time
        case targetFish
        case caughtFish
        case count
        case sizeNote
        case lureUsed
        case method
        // ↓ lat/lon/hasCoord 是 Java 版**漏掉**的字段，本移植补齐（见文件头差异说明 1）
        case lat
        case lon
        case hasCoord
        case weather
        case wind
        case pressure
        case airTemp
        case waterTemp
        case waterNote
        case note
        case rating
        // ↓ createdAt 同样是 Java 版没有导出的字段
        case createdAt
    }

    public init() {
    }

    /// 逐字段解码；缺失字段给默认值，数字字段兼容"整数写成小数"的情况。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try CatchRecord.flexInt64(c, .id, 0)
        spotId = try CatchRecord.flexInt64(c, .spotId, -1)
        spotName = try c.decodeIfPresent(String.self, forKey: .spotName) ?? ""
        time = try CatchRecord.flexInt64(c, .time, 0)
        targetFish = try c.decodeIfPresent(String.self, forKey: .targetFish) ?? ""
        caughtFish = try c.decodeIfPresent(String.self, forKey: .caughtFish) ?? ""
        count = try CatchRecord.flexInt(c, .count, 0)
        sizeNote = try c.decodeIfPresent(String.self, forKey: .sizeNote) ?? ""
        lureUsed = try c.decodeIfPresent(String.self, forKey: .lureUsed) ?? ""
        method = try c.decodeIfPresent(String.self, forKey: .method) ?? ""
        lat = try CatchRecord.flexDouble(c, .lat, 0)
        lon = try CatchRecord.flexDouble(c, .lon, 0)
        hasCoord = try c.decodeIfPresent(Bool.self, forKey: .hasCoord) ?? false
        weather = try c.decodeIfPresent(String.self, forKey: .weather) ?? ""
        wind = try c.decodeIfPresent(String.self, forKey: .wind) ?? ""
        pressure = try c.decodeIfPresent(String.self, forKey: .pressure) ?? ""
        airTemp = try c.decodeIfPresent(String.self, forKey: .airTemp) ?? ""
        waterTemp = try c.decodeIfPresent(String.self, forKey: .waterTemp) ?? ""
        waterNote = try c.decodeIfPresent(String.self, forKey: .waterNote) ?? ""
        note = try c.decodeIfPresent(String.self, forKey: .note) ?? ""
        rating = try CatchRecord.flexInt(c, .rating, 0)
        createdAt = try CatchRecord.flexInt64(c, .createdAt, 0)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(spotId, forKey: .spotId)
        try c.encode(spotName, forKey: .spotName)
        try c.encode(time, forKey: .time)
        try c.encode(targetFish, forKey: .targetFish)
        try c.encode(caughtFish, forKey: .caughtFish)
        try c.encode(count, forKey: .count)
        try c.encode(sizeNote, forKey: .sizeNote)
        try c.encode(lureUsed, forKey: .lureUsed)
        try c.encode(method, forKey: .method)
        try c.encode(lat, forKey: .lat)
        try c.encode(lon, forKey: .lon)
        try c.encode(hasCoord, forKey: .hasCoord)
        try c.encode(weather, forKey: .weather)
        try c.encode(wind, forKey: .wind)
        try c.encode(pressure, forKey: .pressure)
        try c.encode(airTemp, forKey: .airTemp)
        try c.encode(waterTemp, forKey: .waterTemp)
        try c.encode(waterNote, forKey: .waterNote)
        try c.encode(note, forKey: .note)
        try c.encode(rating, forKey: .rating)
        try c.encode(createdAt, forKey: .createdAt)
    }

    // MARK: - 类型宽松解码（与 FishingSpot 里的实现同源，见那边的注释）

    private static func flexInt(_ c: KeyedDecodingContainer<CodingKeys>,
                                _ key: CodingKeys, _ def: Int) throws -> Int {
        guard c.contains(key), try !c.decodeNil(forKey: key) else { return def }
        if let v = try? c.decode(Int.self, forKey: key) { return v }
        if let d = try? c.decode(Double.self, forKey: key) { return Int(d.rounded()) }
        if let s = try? c.decode(String.self, forKey: key) {
            if let v = Int(s.trimmingCharacters(in: .whitespaces)) { return v }
            if let d = Double(s.trimmingCharacters(in: .whitespaces)) { return Int(d.rounded()) }
        }
        throw DecodingError.typeMismatch(Int.self,
            DecodingError.Context(codingPath: c.codingPath + [key],
                                  debugDescription: "期望整数（或可转成整数的字符串）"))
    }

    private static func flexInt64(_ c: KeyedDecodingContainer<CodingKeys>,
                                  _ key: CodingKeys, _ def: Int64) throws -> Int64 {
        guard c.contains(key), try !c.decodeNil(forKey: key) else { return def }
        if let v = try? c.decode(Int64.self, forKey: key) { return v }
        if let d = try? c.decode(Double.self, forKey: key) { return Int64(d.rounded()) }
        if let s = try? c.decode(String.self, forKey: key) {
            if let v = Int64(s.trimmingCharacters(in: .whitespaces)) { return v }
            if let d = Double(s.trimmingCharacters(in: .whitespaces)) { return Int64(d.rounded()) }
        }
        throw DecodingError.typeMismatch(Int64.self,
            DecodingError.Context(codingPath: c.codingPath + [key],
                                  debugDescription: "期望整数（或可转成整数的字符串）"))
    }

    private static func flexDouble(_ c: KeyedDecodingContainer<CodingKeys>,
                                   _ key: CodingKeys, _ def: Double) throws -> Double {
        guard c.contains(key), try !c.decodeNil(forKey: key) else { return def }
        if let d = try? c.decode(Double.self, forKey: key) { return d }
        if let s = try? c.decode(String.self, forKey: key), let d = Double(s) { return d }
        throw DecodingError.typeMismatch(Double.self,
            DecodingError.Context(codingPath: c.codingPath + [key],
                                  debugDescription: "期望数字（或可转成数字的字符串）"))
    }

    // MARK: - 展示辅助

    /// 这条记录是否等于"什么都没记"（空军且没填鱼种）
    public var isBlank: Bool {
        return count <= 0 && caughtFish.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 环境信息拼成一行，卡片与详情共用。
    ///
    /// 顺序与 Java 一致：天气 · 风 · 气压 · 气温 · 水温 · 水情，用 " · " 连接。
    /// Python/Java 风格的 `appendIf` 在这里直接写成数组 + joined：
    /// 分隔符的插入规则（只在非首项前加）与 Java 的 `sb.length() > 0` 判断等价。
    public func environmentLine() -> String {
        var parts: [String] = []
        if !weather.isEmpty { parts.append(weather) }
        if !wind.isEmpty { parts.append(wind) }
        if !pressure.isEmpty { parts.append("气压 " + pressure) }
        if !airTemp.isEmpty { parts.append("气温 " + airTemp) }
        if !waterTemp.isEmpty { parts.append("水温 " + waterTemp) }
        if !waterNote.isEmpty { parts.append(waterNote) }
        return parts.joined(separator: " · ")
    }

    /// 用于搜索的合并文本（小写化语义差异见 `FishingSpot.searchBlob` 的注释）
    public func searchBlob() -> String {
        return (spotName + " " + targetFish + " " + caughtFish + " " + lureUsed + " "
            + method + " " + note + " " + waterNote).lowercased()
    }
}
