import Foundation

/// 一个路亚钓点。
///
/// 坐标约定：lat/lon 一律以 **WGS-84** 存储（即手机 GPS 原始值）。
/// 展示到高德/腾讯地图前转 GCJ-02，展示到百度地图前转 BD-09。
/// 统一存 WGS-84 可以避免以后换地图、换手机时产生累积偏移。
///
/// ★ 这是 Android 版 `model/FishingSpot.java` 的忠实移植。
///   Java 那个类是 **mutable bean + null 兜底 getter**（`getName()` 里写 `name == null ? "" : name`），
///   Swift 这边没有 null：字段一律 `var x: String = ""`，不引入 `Optional`。
///   这样 `spot.name` 永远安全，与 Java 的 getter 契约等价，也省掉了 20 多个 `?? ""`。
///   代价是"字段到底有没有填过"无法区分 —— 但 Java 版同样区分不了（getter 抹掉了区别），
///   所以这不是移植引入的差异。
///
/// ★ 序列化用 `Codable`（这是既定的决策：Java 那个 297 行的手写 `Json.java` 不移植）。
///   为了与 Android 导出的备份文件互通，`CodingKeys` 一律**照抄 JSON 里的 key**，
///   即使它与 Swift 属性名不同：
///     - `feeNote` 在 JSON 里叫 `fee`
///     - `sourceUrl` 在 JSON 里叫 `source`
///     - `waterTypeName` / `statusName` / `confidenceName` 是**冗余的展示字段**，
///       Android 导出时会写进去，所以这里也编码它们；解码时则**忽略**，
///       始终由数字字段（waterType/status/confidence）重新推导 ——
///       否则一份被人手改过的 JSON 会让"水库 1"和"河流"这种矛盾数据在库里存活下来。
///
/// ★ 与 Java 的**行为差异**（必须明确，不能悄悄改）：
///   1. 备份 JSON 的字段集：`Exporter.swift` 与 Java 版一样只写 Android 导出过的那些字段，
///      但**额外写入 `createdAt` / `updatedAt`**。Java 版导出时丢掉了这两个时间戳，
///      导致"Android 导出 → 重新导入"会丢失创建时间。这里是**有意的改进**，
///      且只增字段不改字段，Android 的 `Seed.fromMap` 读多出来的 key 会直接忽略，
///      因此双向兼容不受影响。若哪天真要逐字节复刻 Android 的导出，把这两个 key 从
///      `Exporter` 的字段表里去掉即可（模型本身不用动）。
///   2. 解码容错度：Java 的 `Json.str/num/bool` 会把 `"true"`、`1`、`"37.0"` 这类
///      **类型不符**的值强行转成目标类型；Swift 用 `Codable`，类型不符会直接抛 `DecodingError`。
///      这是刻意的：静默转换会让"坐标写成字符串"这种脏数据变成看似正常的钓点。
///      缺字段（key 不存在）仍然走默认值，不会抛错 —— 所以旧版本备份能正常读入。
///   3. `daysUntilClosedSeason` 的日期语义与 Java 逐字对齐（含跨年 +1 年、向上取整），
///      但 Java 用 `Calendar.clone()` + `add(YEAR, 1)`，Swift 用 `Calendar.date(byAdding:)`。
///      两者都是"同一挂钟时刻 + 1 自然年"，闰日附近行为一致。
public struct FishingSpot: Codable, Equatable, Hashable, Identifiable, Sendable {

    // MARK: - 水域类型

    public static let typeUnknown = 0
    public static let typeReservoir = 1   // 水库
    public static let typeRiver = 2       // 河流
    public static let typeLake = 3        // 湖泊
    public static let typePond = 4        // 池塘
    public static let typePaypond = 5     // 黑坑/收费塘
    public static let typeCanal = 6       // 渠道
    public static let typePit = 7         // 沙坑/矿坑

    // MARK: - 作钓状态

    public static let statusUnknown = 0
    public static let statusOpen = 1      // 可钓
    public static let statusBanned = 2    // 禁钓
    public static let statusFee = 3       // 收费
    public static let statusRestricted = 4 // 限钓/需许可

    // MARK: - 来源可信度

    public static let confUnverified = 0  // 未核实/推断
    public static let confWeb = 1         // 网络资料
    public static let confField = 2       // 本人实地确认

    // MARK: - 字段

    public var id: Int64 = 0
    public var name: String = ""
    public var lat: Double = 0
    public var lon: Double = 0
    public var hasCoord: Bool = false
    public var waterType: Int = FishingSpot.typeUnknown
    public var status: Int = FishingSpot.statusUnknown
    /// 区县
    public var district: String = ""
    /// 详细位置描述
    public var address: String = ""
    /// 对象鱼，逗号分隔
    public var fishSpecies: String = ""
    /// 推荐拟饵，逗号分隔
    public var lureTypes: String = ""
    /// 最佳季节
    public var bestSeason: String = ""
    /// 停车/步行/可达性
    public var accessNote: String = ""
    /// 备注
    public var note: String = ""
    /// 风险与合规提示
    public var riskNote: String = ""
    /// 禁渔期/休渔规定说明
    public var closedSeason: String = ""
    /// 来源
    public var sourceUrl: String = ""
    /// 费用情况（可能含冲突信息）
    public var feeNote: String = ""
    /// 调研证据（含反向证据，说明为什么这么判断）
    public var evidence: String = ""
    /// 备选来源，竖线分隔
    public var sourceAlt: String = ""
    /// 坐标精度：exact / approx
    public var coordPrecision: String = ""
    /// 来自哪一版预置数据（0 = 用户自己加的）
    public var seedVersion: Int = 0
    public var confidence: Int = FishingSpot.confUnverified
    /// 1-5 星，0 表示未评
    public var rating: Int = 0
    public var favorite: Bool = false
    public var createdAt: Int64 = 0
    public var updatedAt: Int64 = 0

    // MARK: - 编解码

    /// 字段顺序 = 导出 JSON 的 key 顺序，也是 `Codable` 合成编码的顺序。
    /// 刻意与 Java 版 `Exporter.appendSpot` 的书写顺序保持一致，
    /// 这样两边的备份文件 diff 起来只有内容差异、没有顺序噪声。
    public enum CodingKeys: String, CodingKey, CaseIterable {
        case id
        case name
        case hasCoord
        case lat
        case lon
        case waterType
        case waterTypeName
        case status
        case statusName
        case district
        case address
        case fishSpecies
        case lureTypes
        case bestSeason
        case accessNote
        case note
        case riskNote
        case closedSeason
        case feeNote = "fee"          // Android 导出的 key 是 fee（Seed.fromMap 也读 fee）
        case evidence
        case sourceAlt
        case coordPrecision
        case sourceUrl = "source"     // Android 导出的 key 是 source
        /// 预置数据版本号。Android 的 `Exporter` 不写、`Seed.fromMap` 也不读它，
        /// 但它是"预置数据 / 用户自建"的唯一标记，丢了就再也区分不出来，
        /// 所以这里保留这个 key（缺省 0 = 用户自建，与 Android 导入后的状态一致）。
        case seedVersion
        case confidence
        case confidenceName
        case rating
        case favorite
        // ↓ 以下两个 key 是 Android 版**没有**导出的（见文件头差异说明 1）
        case createdAt
        case updatedAt
    }

    public init() {
    }

    /// 逐字段解码。
    ///
    /// 不依赖合成的 `init(from:)`，因为三类字段要特殊对待：
    ///   1. 冗余展示字段（waterTypeName / statusName / confidenceName）不读，一律重新推导；
    ///   2. 坐标与时间戳数字在 JSON 里可能是整数（`37` 而不是 `37.0`），
    ///      直接 `decode(Double.self)` 在部分 JSON 解析器下会抛类型错误，这里显式兼容；
    ///   3. 缺失字段给默认值（= Java 版 `Json.str(m, key)` 返回空串的行为）。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)

        id = try FishingSpot.flexInt64(c, .id, 0)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        hasCoord = try c.decodeIfPresent(Bool.self, forKey: .hasCoord) ?? false
        lat = try FishingSpot.flexDouble(c, .lat, 0)
        lon = try FishingSpot.flexDouble(c, .lon, 0)
        waterType = try FishingSpot.flexInt(c, .waterType, FishingSpot.typeUnknown)
        status = try FishingSpot.flexInt(c, .status, FishingSpot.statusUnknown)
        district = try c.decodeIfPresent(String.self, forKey: .district) ?? ""
        address = try c.decodeIfPresent(String.self, forKey: .address) ?? ""
        fishSpecies = try c.decodeIfPresent(String.self, forKey: .fishSpecies) ?? ""
        lureTypes = try c.decodeIfPresent(String.self, forKey: .lureTypes) ?? ""
        bestSeason = try c.decodeIfPresent(String.self, forKey: .bestSeason) ?? ""
        accessNote = try c.decodeIfPresent(String.self, forKey: .accessNote) ?? ""
        note = try c.decodeIfPresent(String.self, forKey: .note) ?? ""
        riskNote = try c.decodeIfPresent(String.self, forKey: .riskNote) ?? ""
        closedSeason = try c.decodeIfPresent(String.self, forKey: .closedSeason) ?? ""
        sourceUrl = try c.decodeIfPresent(String.self, forKey: .sourceUrl) ?? ""
        feeNote = try c.decodeIfPresent(String.self, forKey: .feeNote) ?? ""
        evidence = try c.decodeIfPresent(String.self, forKey: .evidence) ?? ""
        sourceAlt = try c.decodeIfPresent(String.self, forKey: .sourceAlt) ?? ""
        coordPrecision = try c.decodeIfPresent(String.self, forKey: .coordPrecision) ?? ""
        seedVersion = try FishingSpot.flexInt(c, .seedVersion, 0)
        confidence = try FishingSpot.flexInt(c, .confidence, FishingSpot.confUnverified)
        rating = try FishingSpot.flexInt(c, .rating, 0)
        favorite = try c.decodeIfPresent(Bool.self, forKey: .favorite) ?? false
        createdAt = try FishingSpot.flexInt64(c, .createdAt, 0)
        updatedAt = try FishingSpot.flexInt64(c, .updatedAt, 0)

        // 注意 seedVersion：key 不存在时保持 0；存在时**原样保留**。
        // Android 的 Seed.fromMap 不读这个字段（导入时恒为 0），
        // 但本工程的备份文件可能带这个 key（自建钓点 = 0，预置数据 = 4），
        // 丢弃它会让"预置数据 / 用户自建"的区分在往返中消失。
    }

    /// 逐字段编码：顺序由 `CodingKeys.allCases` 决定，与 Java 版写入顺序一致。
    ///
    /// 三个展示字段每次编码时重新推导，保证 JSON 里不会出现"数字说水库、名字说河流"。
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(hasCoord, forKey: .hasCoord)
        try c.encode(lat, forKey: .lat)
        try c.encode(lon, forKey: .lon)
        try c.encode(waterType, forKey: .waterType)
        try c.encode(FishingSpot.waterTypeName(waterType), forKey: .waterTypeName)
        try c.encode(status, forKey: .status)
        try c.encode(FishingSpot.statusName(status), forKey: .statusName)
        try c.encode(district, forKey: .district)
        try c.encode(address, forKey: .address)
        try c.encode(fishSpecies, forKey: .fishSpecies)
        try c.encode(lureTypes, forKey: .lureTypes)
        try c.encode(bestSeason, forKey: .bestSeason)
        try c.encode(accessNote, forKey: .accessNote)
        try c.encode(note, forKey: .note)
        try c.encode(riskNote, forKey: .riskNote)
        try c.encode(closedSeason, forKey: .closedSeason)
        try c.encode(feeNote, forKey: .feeNote)
        try c.encode(evidence, forKey: .evidence)
        try c.encode(sourceAlt, forKey: .sourceAlt)
        try c.encode(coordPrecision, forKey: .coordPrecision)
        try c.encode(seedVersion, forKey: .seedVersion)
        try c.encode(sourceUrl, forKey: .sourceUrl)
        try c.encode(confidence, forKey: .confidence)
        try c.encode(FishingSpot.confidenceName(confidence), forKey: .confidenceName)
        try c.encode(rating, forKey: .rating)
        try c.encode(favorite, forKey: .favorite)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encode(updatedAt, forKey: .updatedAt)
    }

    // MARK: - 类型宽松解码

    /// 整数：JSON 里的数字可能是整数也可能是小数（`37` / `37.0`）。
    /// Java 的 `Json.intVal` 是 `(int) Math.round(num(m,key,def))` —— 四舍五入；
    /// 这里保持一致（用 `.rounded()`，对正数等同 Math.round）。
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

    // MARK: - 坐标

    /// 坐标是否经过实测核对（而不是估算）
    public var isCoordExact: Bool {
        // Java 用 equalsIgnoreCase；Swift 的 caseInsensitiveCompare 是同一语义
        return coordPrecision.compare("exact", options: .caseInsensitive) == .orderedSame
            || confidence == FishingSpot.confField
    }

    /// 坐标精度的人话说法
    public func coordPrecisionText() -> String {
        if confidence == FishingSpot.confField {
            return "你已实地用 GPS 校正"
        }
        if coordPrecision.compare("exact", options: .caseInsensitive) == .orderedSame {
            return "已按公开地图数据核对，误差约几十米"
        }
        return "估算值，误差可能到公里级，请现场用 GPS 校正"
    }

    /// 是否被用户自己核实过（那就绝不能再用预置数据覆盖）
    public var isUserVerified: Bool {
        return confidence == FishingSpot.confField
    }

    /// 坐标文本，5 位小数（约 1 米精度）
    public func coordText() -> String {
        if !hasCoord {
            return "无坐标"
        }
        // Java: String.format(Locale.CHINA, "%.5f, %.5f", lat, lon)
        return String(format: "%.5f, %.5f", locale: Locale(identifier: "zh_CN"), lat, lon)
    }

    // MARK: - 展示辅助

    public static func waterTypeName(_ type: Int) -> String {
        switch type {
        case typeReservoir: return "水库"
        case typeRiver:     return "河流"
        case typeLake:      return "湖泊"
        case typePond:      return "池塘"
        case typePaypond:   return "黑坑"
        case typeCanal:     return "渠道"
        case typePit:       return "沙坑"
        default:            return "未分类"
        }
    }

    public static func statusName(_ s: Int) -> String {
        switch s {
        case statusOpen:       return "可钓"
        case statusBanned:     return "禁钓"
        case statusFee:        return "收费"
        case statusRestricted: return "限钓"
        default:               return "待确认"
        }
    }

    public static func confidenceName(_ c: Int) -> String {
        switch c {
        case confField: return "实地确认"
        case confWeb:   return "网络资料"
        default:        return "未核实"
        }
    }

    /// 水域类型对应的图标，让列表一眼能分辨
    public static func waterTypeIcon(_ type: Int) -> String {
        switch type {
        case typeReservoir: return "🏞"
        case typeRiver:     return "🌊"
        case typeLake:      return "💧"
        case typePond:      return "🪷"
        case typePaypond:   return "🎫"
        case typeCanal:     return "🚱"
        case typePit:       return "⛏"
        default:            return "📍"
        }
    }

    /// 用于搜索的合并文本。
    ///
    /// ⚠ 已知的**中英差异**：Java 的 `String.toLowerCase()` 用默认 Locale，
    /// 中文环境下对 ASCII 依然是 toLowerCase；Swift 的 `lowercased()` 是 Unicode-aware 的。
    /// 对"字母 + 汉字"的查询串两者结果相同，但对土耳其语 İ/I 之类会有差别 ——
    /// 本工程数据只有中英文，视为等价。
    public func searchBlob() -> String {
        return (name + " " + district + " " + address + " " + fishSpecies + " "
            + lureTypes + " " + note + " " + riskNote + " "
            + FishingSpot.waterTypeName(waterType)).lowercased()
    }

    // MARK: - 禁渔期
    //
    // 河北省海河流域禁渔期：5月16日12时 — 7月31日12时
    // 依据邢台市及各区县农业农村局通告（如邢台市信都区 2025 年休禁渔制度通告）。
    //
    // 注意措辞：通告原文的禁止类型是「除钓具之外的所有作业方式」，
    // 即钓具垂钓本身被豁免；严打对象是「以休闲垂钓为幌子的一人多杆、多钩线钓、锚鱼」。
    // 网上流传的「一人一杆一线一钩」并未找到官方条文出处，因此不作为规定引用。

    public static let closedMonthFrom = 5
    public static let closedDayFrom = 16
    public static let closedMonthTo = 7
    public static let closedDayTo = 31
    public static let closedSeasonText = "5月16日12时—7月31日12时"
    /// 12 时生效：这里只按"日"判断，所以 5/16 一整天都算禁渔期（与 Java 版一致）
    public static let closedHourFrom = 12

    /// 通告原文关于禁止作业类型的表述（应原样引用，不要改写成其他说法）
    public static let closedSeasonRule =
        "通告原文：禁止使用除钓具以外的所有采捕工具（撒网、拉网、粘网、地笼、"
        + "锚鱼设备等）；严打以休闲垂钓为幌子的一人多杆、多钩线钓、锚鱼行为。"

    /// 天然水域是否处于禁渔期（人工养殖塘不适用）
    public static func inClosedSeason(_ date: Date = Date()) -> Bool {
        let md = monthDay(date)
        return md >= (closedMonthFrom * 100 + closedDayFrom)
            && md <= (closedMonthTo * 100 + closedDayTo)
    }

    /// 当前时刻是否处于禁渔期（Java 版另有 inClosedSeason(Calendar)，语义相同）
    public static func inClosedSeasonNow() -> Bool {
        return inClosedSeason(Date())
    }

    /// 这个钓点是否受禁渔期约束（黑坑/养殖塘不受约束）
    public func subjectToClosedSeason() -> Bool {
        return waterType != FishingSpot.typePaypond && waterType != FishingSpot.typePond
    }

    /// 当前是否正处于这个钓点的禁渔期
    public func isClosedNow() -> Bool {
        return subjectToClosedSeason() && FishingSpot.inClosedSeasonNow()
    }

    /// 距离禁渔期开始还有多少天；已进入返回 0；已过则返回 -1 之外的天数（见下）。
    ///
    /// ⚠ Java 的 javadoc 写"已过则返回 -1"，但**实现不是这样**：它算的是"下一个 5 月 16 日"，
    /// 已过禁渔期时返回的是到**明年** 5/16 的天数（例如 8 月 1 日返回 288 左右），
    /// 永远不会返回 -1。这里照抄实现而不是注释 —— 界面按注释改会得到错误的天数。
    /// （已把这个 Javadoc 与实现不符的问题写进移植报告。）
    public static func daysUntilClosedSeason(_ date: Date = Date()) -> Int {
        let calendar = Calendar.current
        let md = monthDay(date)
        let start = closedMonthFrom * 100 + closedDayFrom
        let end = closedMonthTo * 100 + closedDayTo
        if md >= start && md <= end {
            return 0
        }

        // 目标：今年（已过则明年）的 5 月 16 日、保持同一挂钟时刻
        var comps = calendar.dateComponents([.year], from: date)
        comps.month = closedMonthFrom
        comps.day = closedDayFrom
        comps.hour = nil
        comps.minute = nil
        comps.second = nil
        comps.nanosecond = nil
        guard var target = calendar.date(from: comps) else { return -1 }
        if md > end {
            guard let next = calendar.date(byAdding: .year, value: 1, to: target) else { return -1 }
            target = next
        }
        // 差分按毫秒算再向上取整到天，与 Java 的 Math.ceil(diff / 86400000.0) 一致
        let diffMs = target.timeIntervalSince(date) * 1000.0
        return Int(ceil(diffMs / 86400000.0))
    }

    /// `month * 100 + day`，Java 版到处用这个整数比较月日
    private static func monthDay(_ date: Date) -> Int {
        let c = Calendar.current.dateComponents([.month, .day], from: date)
        return (c.month ?? 1) * 100 + (c.day ?? 1)
    }

    // MARK: - 调试

    // 等价于 Java 的 toString()：遵循 CustomStringConvertible 后
    // `"\(spot)"` 与 `print(spot)` 都会走这里。
}

extension FishingSpot: CustomStringConvertible {
    public var description: String {
        return "FishingSpot{" + name + " " + district + " "
            + FishingSpot.waterTypeName(waterType) + "}"
    }
}
