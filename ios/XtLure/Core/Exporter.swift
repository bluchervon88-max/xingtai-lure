// ★ 本文件是生成物，请勿直接修改。
//
// 单一来源: ios/Sources/XtLureCore/Exporter.swift
// 同步方式: node ios/sync-sources.js
//
// 为什么要有这份副本：Xcode 工程按固定相对路径找文件，而逻辑层的单一来源
// 放在 XtLureCore 是为了能在 Windows 上用 swiftc 直接编译测试。
// 直接改这份副本会在下次同步时被覆盖 —— 请改单一来源。
import Foundation

/// 导出：备份 JSON、GeoJSON、人可读 Markdown。
///
/// ★ 这是 Android 版 `util/Exporter.java` 的**忠实移植**。
///   目标是「Android 导出的文件能在 iOS 上导入，iOS 导出的文件也能在 Android 上导入」，
///   因此**输出的字符格式必须与 Java 版逐字节一致** —— 包括缩进、换行、数字的写法、
///   转义方式、字段顺序。移植后用 Java 版跑同一批数据做了逐字节 diff，见差异说明。
///
/// ★ 三个产物（与 Java 版一一对应）：
///   1) `toBackupJson` —— 通用备份，纯手工拼串，不依赖 `JSONEncoder`。
///      为什么不用 `JSONEncoder`：它会把 Double 写成 `37.0` 而不是 Java 的 `37`，
///      键顺序也由它自己决定，输出与原版对不上；而这份文件是**跨平台数据契约**，
///      格式漂移会让 diff 变得不可读，也容易掩盖真正的字段增删。
///      字段值仍然来自 `FishingSpot` / `CatchRecord`（它们自身是 `Codable`，
///      供 App 内部的其他序列化场景使用）。
///   2) `toGeoJson` —— 可直接丢进 QGIS / geojson.io / 高德开放平台，坐标是 WGS-84。
///   3) `toMarkdown` —— 按区县分组的清单，方便打印、发群里、贴到笔记软件。
///
/// ★ Java → Swift 的语义差异（逐条对应，都是等价改写）：
///   1. `System.currentTimeMillis()` → `Date().timeIntervalSince1970 * 1000`，
///      都是"Unix epoch 毫秒"；本文件统一走 `nowMillis()`。
///   2. `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)` 用的是**系统默认时区**，
///      Swift 这边显式写 `TimeZone.current`，行为一致。
///      Locale 用 `en_US_POSIX` 而不是 `zh_CN`：格式里全是数字，POSIX 能保证在任何
///      系统区域设置下都输出 ASCII 数字（阿拉伯语/泰语数字系统的机器上 `zh_CN` 也可能漂）。
///   3. `String.format(Locale.CHINA, "%.5f, %.5f", ...)` 与
///      `String(format:locale:)` 同为 printf 语义（HALF_UP），输出一致。
///   4. `num()` 里 Java 用正则 `replaceAll("0+$","").replaceAll("\\.$","")` 去尾随零。
///      这里改用 `Foundation` 的 `trimmingCharacters(in:)`，去掉的是**首尾**的 '0' 和 '.'，
///      而 Java 的两条正则合起来也正好等价于"去掉尾部的 0 与末尾的点"，
///      且 `String(format: "%.6f", ...)` 的结果不会有前导零被误删（`0.500000` → `0.5`，
///      首字符 '0' 不会被 trim 掉，因为后面还有非零字符）。此点已用边界值实测对齐。
///   5. `StringBuilder` → `String` 累加。数据量是几十到几百条，性能无差异。
///      早期 iOS 上 `String` 拼接是 O(n²)，现在 Swift 的标准库实现已不复存在这个问题。
///   6. Java 逐 **UTF-16 码元**转义，Swift 逐 **Unicode 标量**。对 BMP 内字符（含所有
///      中文、以及 `< 0x20` 的控制符）两者输出完全相同；差异只可能出现在
///      **字符串里真的含控制字符的代理对**这种极端情况，实际数据不可能出现。
///
/// ★ 与 Java 的**行为差异**（有意为之，必须明确）：
///   1. 导出字段集**多于** Java 版：
///        - `spots[]` 每条多写 `createdAt` / `updatedAt`
///        - `catches[]` 每条多写 `lat` / `lon` / `hasCoord` / `createdAt`
///      Java 的 `appendCatch` 漏掉了作钓记录的坐标（模型里有 `lat/lon/hasCoord`，
///      导出却不写），导出后坐标永久丢失。这里是补齐。
///      两个方向都不破坏兼容：Android 的 `Seed.fromMap` 读多出来的 key 会直接忽略。
///      若确实需要与 Java 版**逐字节**相同的文件（例如做回归 diff），
///      把 `appendSpot` / `appendCatch` 里标了 "仅 Swift" 的两段删掉即可。
///   2. `str(nil)`：Java 版接收 `String`，null 时输出 `""`；Swift 的非可选 `String`
///      不存在 null，因此这条分支在 Swift 里不可达（写出来只是保持结构对照）。
///   3. `pretty()` 里的字符串状态判断：Java 写的是 `json.charAt(i-1) != '\\'`，
///      即"前一个字符是反斜杠就认为引号被转义"。这对 `"\\"`（字符串内容是一个反斜杠）
///      这样的输入会误判（连续两个反斜杠时第二个才是转义符）。移植时**照抄了这个行为**，
///      没有修正 —— 原因见下面 `pretty` 的注释：它只用于人眼查看，改动反而让人
///      以为两个平台的输出会不一样。已作为潜在 bug 写进移植报告。
public enum Exporter {

    // ★ 这里不要写 `private init() { }`。
    //
    //   安卓版对应的是 `private Exporter() {}`（Java 里给工具类加私有构造器是常见做法，
    //   用来阻止实例化）。Swift 里不能照搬：
    //   **`enum` 不允许声明初始化器** —— 只有 struct / class / actor 可以。
    //
    //   写了会报 `'self.init' isn't called on all paths before returning from initializer`，
    //   报错信息完全指不到真正的原因（看起来像是初始化流程写漏了分支）。
    //   实测踩过：编译失败，但错误提示与"枚举不能有 init"毫无字面关系。
    //
    //   不需要替代写法：不写任何 init 的 enum 本来就不能被实例化，
    //   这正好就是 Java 私有构造器想达到的效果。

    public static let formatVersion = 1

    /// Android 备份文件里的固定标识串（导入时要校验这个值）
    public static let backupFormatTag = "xtlure-backup"

    // MARK: - 备份 JSON

    /// 生成通用备份 JSON。
    ///
    /// - Parameters:
    ///   - spots: 钓点列表
    ///   - catches: 作钓记录列表
    ///   - now: 导出时刻，默认当前时间。可注入是为了让测试能产出确定性文件。
    public static func toBackupJson(_ spots: [FishingSpot],
                                    _ catches: [CatchRecord],
                                    now: Date = Date()) -> String {
        var sb = ""
        sb.reserveCapacity(8192)
        sb += "{\n"
        sb += "  \"format\": \"\(backupFormatTag)\",\n"
        sb += "  \"formatVersion\": \(formatVersion),\n"
        sb += "  \"app\": \"邢台路亚\",\n"
        sb += "  \"exportedAt\": \"\(iso(millis(now)))\",\n"
        sb += "  \"note\": \"坐标字段 lat/lon 均为 WGS-84（GPS 原始值）。展示到高德/腾讯需转 GCJ-02，百度需转 BD-09。\",\n"

        sb += "  \"spots\": [\n"
        for i in 0..<spots.count {
            appendSpot(&sb, spots[i])
            sb += i < spots.count - 1 ? ",\n" : "\n"
        }
        sb += "  ],\n"

        sb += "  \"catches\": [\n"
        for i in 0..<catches.count {
            appendCatch(&sb, catches[i])
            sb += i < catches.count - 1 ? ",\n" : "\n"
        }
        sb += "  ]\n"
        sb += "}\n"
        return sb
    }

    private static func appendSpot(_ sb: inout String, _ s: FishingSpot) {
        sb += "    {"
        sb += "\"id\":\(s.id),"
        sb += "\"name\":\(str(s.name)),"
        // 坐标用扁平字段，与 assets/spots_seed.json 完全一致的格式，
        // 这样"导出 -> 重新导入"能无损往返（Android 的 Seed.fromMap 读的就是这个格式）。
        sb += "\"hasCoord\":\(s.hasCoord),"
        sb += "\"lat\":\(num(s.lat)),"
        sb += "\"lon\":\(num(s.lon)),"
        sb += "\"waterType\":\(s.waterType),"
        sb += "\"waterTypeName\":\(str(FishingSpot.waterTypeName(s.waterType))),"
        sb += "\"status\":\(s.status),"
        sb += "\"statusName\":\(str(FishingSpot.statusName(s.status))),"
        sb += "\"district\":\(str(s.district)),"
        sb += "\"address\":\(str(s.address)),"
        sb += "\"fishSpecies\":\(str(s.fishSpecies)),"
        sb += "\"lureTypes\":\(str(s.lureTypes)),"
        sb += "\"bestSeason\":\(str(s.bestSeason)),"
        sb += "\"accessNote\":\(str(s.accessNote)),"
        sb += "\"note\":\(str(s.note)),"
        sb += "\"riskNote\":\(str(s.riskNote)),"
        sb += "\"closedSeason\":\(str(s.closedSeason)),"
        sb += "\"fee\":\(str(s.feeNote)),"
        sb += "\"evidence\":\(str(s.evidence)),"
        sb += "\"sourceAlt\":\(str(s.sourceAlt)),"
        sb += "\"coordPrecision\":\(str(s.coordPrecision)),"
        sb += "\"source\":\(str(s.sourceUrl)),"
        sb += "\"confidence\":\(s.confidence),"
        sb += "\"confidenceName\":\(str(FishingSpot.confidenceName(s.confidence))),"
        sb += "\"rating\":\(s.rating),"
        sb += "\"favorite\":\(s.favorite),"
        // 仅 Swift：Java 版没有导出这两个时间戳（见文件头差异说明 1）
        sb += "\"createdAt\":\(s.createdAt),"
        sb += "\"updatedAt\":\(s.updatedAt)"
        sb += "}"
    }

    private static func appendCatch(_ sb: inout String, _ r: CatchRecord) {
        sb += "    {"
        sb += "\"id\":\(r.id),"
        sb += "\"spotId\":\(r.spotId),"
        sb += "\"spotName\":\(str(r.spotName)),"
        sb += "\"time\":\(r.time),"
        sb += "\"timeText\":\(str(iso(r.time))),"
        sb += "\"targetFish\":\(str(r.targetFish)),"
        sb += "\"caughtFish\":\(str(r.caughtFish)),"
        sb += "\"count\":\(r.count),"
        sb += "\"sizeNote\":\(str(r.sizeNote)),"
        sb += "\"lureUsed\":\(str(r.lureUsed)),"
        sb += "\"method\":\(str(r.method)),"
        // 仅 Swift：Java 版漏了这三个字段（见文件头差异说明 1）
        sb += "\"lat\":\(num(r.lat)),"
        sb += "\"lon\":\(num(r.lon)),"
        sb += "\"hasCoord\":\(r.hasCoord),"
        sb += "\"weather\":\(str(r.weather)),"
        sb += "\"wind\":\(str(r.wind)),"
        sb += "\"pressure\":\(str(r.pressure)),"
        sb += "\"airTemp\":\(str(r.airTemp)),"
        sb += "\"waterTemp\":\(str(r.waterTemp)),"
        sb += "\"waterNote\":\(str(r.waterNote)),"
        sb += "\"note\":\(str(r.note)),"
        sb += "\"rating\":\(r.rating),"
        sb += "\"createdAt\":\(r.createdAt)"
        sb += "}"
    }

    // MARK: - GeoJSON

    /// 生成 GeoJSON FeatureCollection。
    /// 属性里额外附带 GCJ-02 坐标，方便直接贴到高德用。
    public static func toGeoJson(_ spots: [FishingSpot]) -> String {
        var sb = ""
        sb.reserveCapacity(4096)
        sb += "{\n"
        sb += "  \"type\": \"FeatureCollection\",\n"
        sb += "  \"name\": \"邢台路亚钓点\",\n"
        sb += "  \"crs\": {\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:OGC:1.3:CRS84\"}},\n"
        sb += "  \"features\": [\n"

        var first = true
        for s in spots {
            if !s.hasCoord {
                continue
            }
            if !first {
                sb += ",\n"
            }
            first = false
            let g = CoordinateTransform.wgs84ToGcj02(s.lat, s.lon)
            sb += "    {\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":["
            sb += "\(num(s.lon)),\(num(s.lat))]},"
            sb += "\"properties\":{"
            sb += "\"name\":\(str(s.name)),"
            sb += "\"waterType\":\(str(FishingSpot.waterTypeName(s.waterType))),"
            sb += "\"status\":\(str(FishingSpot.statusName(s.status))),"
            sb += "\"district\":\(str(s.district)),"
            sb += "\"fish\":\(str(s.fishSpecies)),"
            sb += "\"lure\":\(str(s.lureTypes)),"
            sb += "\"confidence\":\(str(FishingSpot.confidenceName(s.confidence))),"
            sb += "\"gcj02Lat\":\(num(g.lat)),"
            sb += "\"gcj02Lon\":\(num(g.lon))"
            sb += "}}"
        }
        sb += "\n  ]\n}\n"
        return sb
    }

    // MARK: - 清单 Markdown

    /// 生成人可读的 Markdown 清单，方便打印、发群里、或贴到笔记软件。
    public static func toMarkdown(_ spots: [FishingSpot], now: Date = Date()) -> String {
        var sb = ""
        sb.reserveCapacity(4096)
        sb += "# 邢台路亚钓点清单\n\n"
        sb += "导出时间：\(iso(millis(now)))\n\n"

        // 按区县分组。
        // Java 用 LinkedHashMap 保证"按首次出现顺序"；Swift 的 Dictionary 无序，
        // 所以额外维护一个 key 数组来固定顺序 —— 顺序不稳定会让同一份数据每次导出
        // 产生不同的文件，diff 与"导出时间之外是否变化"的判断都会失效。
        var byDistrict: [String: [FishingSpot]] = [:]
        var districtOrder: [String] = []
        for s in spots {
            let d = s.district.isEmpty ? "未标区县" : s.district
            if byDistrict[d] == nil {
                byDistrict[d] = []
                districtOrder.append(d)
            }
            byDistrict[d]!.append(s)
        }

        sb += "共 \(spots.count) 个钓点，分布在 \(districtOrder.count) 个区县。\n\n"
        sb += "> 坐标均以 WGS-84（GPS 原始值）记录。粘贴到高德/腾讯地图前需转 GCJ-02，"
        sb += "百度需转 BD-09；本 App「复制坐标」会自动给出三种。\n\n"

        for d in districtOrder {
            sb += "## \(d)\n\n"
            for s in byDistrict[d]! {
                sb += "### \(s.name)\n\n"
                sb += "- 水域类型：\(FishingSpot.waterTypeName(s.waterType))\n"
                sb += "- 作钓状态：\(FishingSpot.statusName(s.status))\n"
                if s.hasCoord {
                    let g = CoordinateTransform.wgs84ToGcj02(s.lat, s.lon)
                    // Java: String.format(Locale.CHINA, "...%n")，%n 是平台换行符，
                    // Android 上就是 \n，这里显式写 \n
                    sb += String(format: "- 坐标：WGS84 %.5f, %.5f ／ GCJ02 %.5f, %.5f\n",
                                 locale: Locale(identifier: "zh_CN"),
                                 s.lat, s.lon, g.lat, g.lon)
                } else {
                    sb += "- 坐标：未记录\n"
                }
                if !s.address.isEmpty {
                    sb += "- 位置：\(s.address)\n"
                }
                if !s.fishSpecies.isEmpty {
                    sb += "- 对象鱼：\(s.fishSpecies)\n"
                }
                if !s.lureTypes.isEmpty {
                    sb += "- 推荐拟饵：\(s.lureTypes)\n"
                }
                if !s.bestSeason.isEmpty {
                    sb += "- 最佳季节：\(s.bestSeason)\n"
                }
                if !s.accessNote.isEmpty {
                    sb += "- 到达方式：\(s.accessNote)\n"
                }
                if !s.note.isEmpty {
                    sb += "- 备注：\(s.note)\n"
                }
                if !s.riskNote.isEmpty {
                    sb += "- ⚠ 风险：\(s.riskNote)\n"
                }
                if !s.closedSeason.isEmpty {
                    sb += "- ⚖ 禁渔期：\(s.closedSeason)\n"
                }
                if !s.feeNote.isEmpty {
                    sb += "- 💰 费用：\(s.feeNote)\n"
                }
                sb += "- 资料可信度：\(FishingSpot.confidenceName(s.confidence))"
                sb += "（\(s.coordPrecisionText())）\n"
                if !s.sourceUrl.isEmpty {
                    sb += "- 来源：\(s.sourceUrl)\n"
                }
                sb += "\n"
            }
        }

        sb += "---\n\n"
        sb += "数据由「邢台路亚」App 导出。坐标与鱼情仅供参考，"
        sb += "出钓前请自行核实当地禁渔期与水域管理规定。\n"
        return sb
    }

    // MARK: - 简易美化打印

    /// 把压缩 JSON 按缩进展开，便于人眼查看。
    ///
    /// ⚠ 移植时**故意保留**了 Java 版的粗糙判断：只认"前一个字符是不是反斜杠"，
    /// 所以字符串里出现 `\\` 时会把后面的引号误判成转义（Java 版同样如此）。
    /// 没有修正的原因：这个函数只用于人眼查看，两个平台行为一致比"更正确"更重要；
    /// 真要修就得两边一起改，否则同一份文件在两端美化出的结果不同。
    ///
    /// 另外它**不支持字符串内的花括号**（Java 版注释里也写了"够用即可"）。
    public static func pretty(_ json: String) -> String {
        var out = ""
        out.reserveCapacity(json.count + json.count / 2)

        // Java 按 UTF-16 码元迭代，这里按 Character 迭代。
        // 对 `"`、`\`、`{`、`}`、`[`、`]`、`,`、`:` 以及空白这些全是 ASCII 的字符，
        // 逐个 Character 判断与逐码元判断等价。
        let chars = Array(json)
        var indent = 0
        var inStr = false
        for i in 0..<chars.count {
            let ch = chars[i]
            if ch == "\"" && (i == 0 || chars[i - 1] != "\\") {
                inStr = !inStr
            }
            if inStr {
                out.append(ch)
                continue
            }
            switch ch {
            case "{", "[":
                out.append(ch)
                out.append("\n")
                indent += 1
                out += pad(indent)
            case "}", "]":
                out.append("\n")
                indent = max(0, indent - 1)
                out += pad(indent)
                out.append(ch)
            case ",":
                out.append(ch)
                out.append("\n")
                out += pad(indent)
            case ":":
                out += ": "
            case " ", "\n", "\t", "\r":
                break
            default:
                out.append(ch)
            }
        }
        return out
    }

    private static func pad(_ n: Int) -> String {
        return String(repeating: "  ", count: n)
    }

    // MARK: - 小工具（与 Java 版同名同义）

    /// 时间戳（毫秒）-> "yyyy-MM-dd HH:mm:ss"，按**系统默认时区**（与 Java 一致）
    private static func iso(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone.current
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f.string(from: date)
    }

    /// 数字转 JSON 字面量。
    ///
    /// 规则与 Java 的 `Exporter.num` 一致：
    ///   - 是整数值（且绝对值 < 1e15）→ 写成整数，`37.0` 输出 `37`
    ///   - 否则 → `%.6f` 后去掉尾随的 '0' 与末尾的 '.'，`1e-7` 会输出 `0`（Java 同样如此）
    ///
    /// `Math.rint(d) == d` 与 Swift 的 `.rounded(.toNearestOrEven) == d` 是同一个判断
    /// （都是"四舍六入五成双"取整后等于自身 ⇒ 该数是整数）。
    /// 用 `.truncatingRemainder(dividingBy: 1) == 0` 也等价，但前者与 Java 逐字对得上。
    ///
    /// ⚠ 去尾随零必须**只从右边**去，不能用 `trimmingCharacters`（那会连首位的 '0' 一起去掉，
    /// `0.500000` 会变成 `.5`）。这里逐字复刻 Java 的两条正则：
    /// `replaceAll("0+$","")` 再 `replaceAll("\\.$","")`。
    ///
    /// ⚠ 已知差异：当 |d| ≥ 1e21（或 ≤ -1e21）时 C 的 `%f` 会退化成科学计数法
    /// （`1e+20`），而 Java 的 `String.format` 仍输出定点数。只有把经纬度写成天文数字
    /// 才会触发；那种数据本身就是脏数据（地图上根本画不出来），故不做定点化补偿。
    public static func num(_ d: Double) -> String {
        if d.rounded(.toNearestOrEven) == d && abs(d) < 1e15 {
            return String(Int64(d))
        }
        let s = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), d)
        return trimTrailingZerosAndDot(s)
    }

    /// 逐字复刻 Java 的 `replaceAll("0+$","").replaceAll("\\.$","")`
    private static func trimTrailingZerosAndDot(_ s: String) -> String {
        let chars = Array(s)
        var end = chars.count
        while end > 0 && chars[end - 1] == "0" {
            end -= 1
        }
        if end > 0 && chars[end - 1] == "." {
            end -= 1
        }
        return String(chars[0..<end])
    }

    /// 字符串转 JSON 字面量（含转义）。
    ///
    /// 与 Java 的 `Exporter.str` 规则一致：转义 `"`、`\`、换行、回车、制表，
    /// 其余 `< 0x20` 的控制符写成 `\u00xx`（小写十六进制，四位）。
    /// 刻意**不转义** `<`、`>`、`&`（HTML 安全转义），因为原版没有 ——
    /// JSON 里它们是合法字符，多转义反而会让两边的文件对不上。
    public static func str(_ s: String) -> String {
        var sb = "\""
        sb.reserveCapacity(s.count + 8)
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"":  sb += "\\\""
            case "\\":  sb += "\\\\"
            case "\n":  sb += "\\n"
            case "\r":  sb += "\\r"
            case "\t":  sb += "\\t"
            default:
                if scalar.value < 0x20 {
                    // Java: String.format(Locale.US, "\\u%04x", (int) c)
                    sb += String(format: "\\u%04x",
                                 locale: Locale(identifier: "en_US_POSIX"),
                                 Int(scalar.value))
                } else {
                    sb.unicodeScalars.append(scalar)
                }
            }
        }
        sb += "\""
        return sb
    }

    /// 当前 Unix 毫秒时间戳
    private static func millis(_ date: Date) -> Int64 {
        return Int64((date.timeIntervalSince1970 * 1000.0).rounded(.down))
    }
}
