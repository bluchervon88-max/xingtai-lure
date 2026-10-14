import Foundation

// 契约测试的第二部分：地图投影、数据模型序列化。
//
// ★ 为什么要单独一个文件
//
//   `main.swift` 覆盖了 coordinateTransform / distance / format / sunMoon / theme，
//   但漏掉了契约文件里的 `mapProjection` 一段，也完全没有覆盖
//   FishingSpot / CatchRecord 的序列化行为。
//
//   这是个真实的漏洞：Java 侧（tools/VectorTest.java）测了 mapProjection，
//   Swift 侧没测 —— 而契约文件存在的全部意义就是**防止两边各写一份期望值**。
//   一边测一边不测，等于契约只覆盖了一半：将来两边算出的投影不一致时，
//   Java 侧的测试会红、Swift 侧却是绿的，正好把问题掩盖过去。
//
//   Swift 里一个编译单元不能有两个文件的顶层语句（只有 main.swift 可以），
//   所以这里的测试写成函数，由 main.swift 调用。

/// 地图投影契约中约定的基准像素画框。
///
/// 用固定的画框尺寸（而不是真实设备尺寸）是有意的：契约里写明了
/// "具体像素尺寸由各平台按自己屏幕推导"，所以**能跨平台比较的只有比例关系**。
/// 固定一个画框，两边算出来的经纬度范围才可以直接对比。
private let contractWidthPx: Float = 390
private let contractHeightPx: Float = 600

/// 契约：地图投影比例
///
/// 核心保证是两个方向的地面米数相同 —— 这条曾经真实出过错：
/// 地图横向被拉伸 1.256 倍（正好是 1/cos(37.2°)），
/// 因为两个方向各自拉满了画框、没做 cos 修正。
func runMapProjectionContract(_ root: [String: Any], _ r: Runner) {
    print("\n=== 契约: mapProjection ===")

    guard let mp = Vec.section(root, "mapProjection") else {
        r.check("mapProjection 段存在", false, "契约文件里找不到这一段")
        return
    }

    // ---- 1x 缩放时的目标视野宽度（公里）
    //
    // ★ 注意是**东西方向（经度）**的宽度，不是纬度。
    //
    //   Java 原版写得很明确：
    //     /** 1× 时东西方向的可见宽度（km） */
    //     static final double TARGET_KM_AT_1X = 42.0;
    //
    //   推导链是：TARGET_KM_AT_1X -> 目标经度跨度 -> pxPerLatDeg
    //            （让 42km 正好铺满画框的宽度）。
    //   所以校验时要算的是**可见经度跨度换算成米**，我第一版算成了纬度跨度，
    //   得到 64.6km 而对不上 42km —— 是测试写错了，不是实现错了。
    //
    //   经度方向的米数要乘 cos(纬度)：同样 1° 经度，在北纬 37° 处的
    //   地面距离只有赤道的 cos(37°)≈0.796 倍。
    if Vec.has(mp, "targetKmAt1x") {
        let want = Vec.num(mp, "targetKmAt1x", 0)
        let vp = GeoViewport(width: contractWidthPx, height: contractHeightPx,
                             zoom: 1, centerLat: 37.225, centerLon: 114.775)
        let metersPerDegLon = GeoViewport.metersPerDegLat * vp.cosLat
        let km = vp.visibleLonSpan * metersPerDegLon / 1000.0
        // 允许 2% 偏差：目标是"约 42 公里"，具体取决于画框宽高比与
        // targetDeg 推导里的 111.32 常量取整。
        let rel = abs(km - want) / want
        r.check("targetKmAt1x（东西向可见宽度）", rel < 0.02,
                String(format: "%.2f km  期望约 %.1f（偏差 %.1f%%）", km, want, rel * 100))
        r.check("GeoViewport.targetKmAt1x 常量",
                abs(GeoViewport.targetKmAt1x - want) < 0.01,
                String(format: "%.2f  期望 %.2f", GeoViewport.targetKmAt1x, want))
    }

    // ---- 缩放范围
    //
    // 下限太小地图会缩成一团、上限太大没有意义。这两个值同时决定了
    // 缩放手势的可用区间，两边必须一致。
    if Vec.has(mp, "minZoom") {
        let want = Float(Vec.num(mp, "minZoom", 0))
        r.check("minZoom", abs(GeoViewport.minZoom - want) < 1e-6,
                String(format: "%.2f  期望 %.2f", Double(GeoViewport.minZoom), Double(want)))
    }
    if Vec.has(mp, "maxZoom") {
        let want = Float(Vec.num(mp, "maxZoom", 0))
        r.check("maxZoom", abs(GeoViewport.maxZoom - want) < 1e-6,
                String(format: "%.0f  期望 %.0f", Double(GeoViewport.maxZoom), Double(want)))
    }

    // ---- 各纬度下两个方向的像素比例必须一致
    for c in Vec.list(mp, "aspectRatios") {
        let id = Vec.str(c, "id", "?")
        let lat = Vec.num(c, "centerLat", 37.225)
        let zoom = Float(Vec.num(c, "zoom", 1.0))
        let tol = Vec.num(c, "expectRatioEqualWithin", 1e-9)

        let vp = GeoViewport(width: contractWidthPx, height: contractHeightPx,
                             zoom: zoom, centerLat: lat, centerLon: 114.775)

        // (1) 契约里写明的比例关系：pxPerLonDeg / pxPerLatDeg 必须等于 cos(纬度)
        //
        // 这是"不变形"的充要条件：经度方向每度对应的像素数，要按纬度
        // 余弦缩小，两个方向的地面距离才一致。
        //
        // 容差由契约给出（现在是 1e-6）。不要在这里再夹一个更小的下限 ——
        // 第一版写成 `max(tol, 1e-9)`，结果把契约里本该生效的 1e-6 又压回了 1e-9，
        // 于是必然误报。容差就该由契约单方面说了算。
        let ratio = Double(vp.pxPerLonDeg) / Double(vp.pxPerLatDeg)
        let expect = cos(lat * Double.pi / 180.0)
        let rel = abs(ratio - expect) / expect
        r.check(id, rel <= tol,
                String(format: "pxLon/pxLat = %.10f  期望 cos(%.3f°) = %.10f  相对差 %.2e（上限 %.0e）",
                       ratio, lat, expect, rel, tol))

        // (2) 再用"可见范围换算成米"交叉验证一次。
        //
        // 上面验的是像素比例，(1) 是对的但也不能保证没有别的错 ——
        // 这里独立地算一遍：可见经度跨度换算成米（要乘 cos 纬度）
        // 与可见纬度跨度换算成米的比值，应当等于画框的宽高比。
        // 两个方向只要有一处算错，这个等式就不成立。
        let metersPerDegLon = GeoViewport.metersPerDegLat * cos(lat * Double.pi / 180.0)
        let widthMeters = vp.visibleLonSpan * metersPerDegLon
        let heightMeters = vp.visibleLatSpan * GeoViewport.metersPerDegLat
        let frameAspect = Double(contractWidthPx) / Double(contractHeightPx)
        let geoAspect = widthMeters / heightMeters
        let aspectRel = abs(geoAspect - frameAspect) / frameAspect
        // 容差与契约一致（1e-6），原因同 (1)：
        // visibleLatSpan / visibleLonSpan 虽是 Double，但源头 pxPerLatDeg 是
        // Float，精度上限就在 1e-7 量级。追求 1e-9 只会得到必然失败。
        r.check(id + " 地面宽高比 = 画框宽高比", aspectRel < 1e-6,
                String(format: "地面 %.6f  画框 %.6f  相对差 %.2e",
                       geoAspect, frameAspect, aspectRel))

        // (3) metersPerPixel 只有一个值，本身就是"两方向一致"的设计体现。
        //     这里确认它落在合理量级（37°N 附近，600px 高看约 42km
        //     -> 每像素约 70 米）。
        let mpp = vp.metersPerPixel
        r.check(id + " metersPerPixel 量级", mpp > 10 && mpp < 500,
                String(format: "%.1f m/px", mpp))
    }
}

/// 禁渔期判定契约（Swift 侧）。
///
/// ★ 为什么这段必须有测试
///
///   判错的后果不是显示难看，而是**用户可能在禁渔期下竿** —— 这是合规问题。
///
///   而且这段逻辑有两个容易写错的地方：
///     1. 边界日：区间是闭区间（月*100+日 的整数比较），起始日和结束日
///        当天都算禁渔期。差一天就是"合法/违法"的区别。
///     2. 跨年：禁渔期在 5~7 月，所以 8~12 月要算到**次年**的 5 月 16 日。
///        这个 +1 年的分支最容易漏，而漏了在禁渔期之外的大部分时间里不会显形。
///
///   与 Java 侧对应：`tools/VectorTestMap.java` 的 `runClosedSeason`。
///   **改一边必须改另一边。**
func runClosedSeasonContract(_ root: [String: Any], _ r: Runner) {
    print("\n=== 契约: closedSeason ===")

    guard let cs = Vec.section(root, "closedSeason") else {
        r.check("closedSeason 段存在", false, "契约文件里找不到这一段")
        return
    }

    // ---- 日期区间判定
    for c in Vec.list(cs, "inSeason") {
        let id = Vec.str(c, "id", "?")
        let dateStr = Vec.str(c, "date", "")
        let want = Vec.bool(c, "expect", false)

        guard let d = dateOf(dateStr) else {
            r.check(id + " 日期解析", false, "无法解析 '\(dateStr)'")
            continue
        }
        let got = FishingSpot.inClosedSeason(d)
        r.check(id, got == want, "\(dateStr) -> \(got)  期望 \(want)")
    }

    // ---- 水域类型是否受约束（黑坑/池塘豁免）
    for c in Vec.list(cs, "subjectTo") {
        let id = Vec.str(c, "id", "?")
        let type = Int(Vec.num(c, "waterType", -1))
        let want = Vec.bool(c, "expect", false)

        var s = FishingSpot()
        s.waterType = type
        let got = s.subjectToClosedSeason()
        r.check(id, got == want,
                "水域类型 \(type) -> \(got)  期望 \(want)"
                    + (want ? "" : "（人工水域豁免）"))
    }

    // ---- 距禁渔期开始的天数
    for c in Vec.list(cs, "daysUntil") {
        let id = Vec.str(c, "id", "?")
        let dateStr = Vec.str(c, "date", "")
        let want = Int(Vec.num(c, "expect", -999))

        guard let d = dateOf(dateStr) else {
            r.check(id + " 日期解析", false, "无法解析 '\(dateStr)'")
            continue
        }
        let got = FishingSpot.daysUntilClosedSeason(d)
        r.check(id, got == want, "\(dateStr) -> \(got) 天  期望 \(want)")
    }

    // ---- 常量与官方文本
    //
    // 文本必须与官方通告一致。「一人一杆一线一钩」这种网上流传但**查不到官方
    // 条文**的说法，早先版本引用过、后来撤掉了，这里锁住防止再被加回去。
    r.check("禁渔期起始常量",
            FishingSpot.closedMonthFrom == 5 && FishingSpot.closedDayFrom == 16,
            "\(FishingSpot.closedMonthFrom)月\(FishingSpot.closedDayFrom)日")
    r.check("禁渔期结束常量",
            FishingSpot.closedMonthTo == 7 && FishingSpot.closedDayTo == 31,
            "\(FishingSpot.closedMonthTo)月\(FishingSpot.closedDayTo)日")
    r.check("禁渔期文本与常量一致",
            FishingSpot.closedSeasonText.contains("5月16日")
                && FishingSpot.closedSeasonText.contains("7月31日"),
            FishingSpot.closedSeasonText)
    r.check("规则引用不含未经证实的说法",
            !FishingSpot.closedSeasonRule.contains("一人一杆一线一钩"),
            "官方条文里查不到「一人一杆一线一钩」，不应作为规定引用")
}

/// 由 "yyyy-MM-dd" 构造 Date。
///
/// ★ 必须固定时区，不能用当前时区。
///
///   契约里写的是"2026-05-16"这样的**日历日期**，判定逻辑只看年月日。
///   如果用本地时区解析，在 UTC-8 之类的时区上，
///   本地时间的 2026-05-16 00:00 换算成 UTC 是 05-16 08:00，
///   而 UTC+14 时区上又会往另一侧偏 —— 一旦实现内部按 UTC 取年月日，
///   测试结果就会随运行机器的时区变化，出现"本机过、CI 挂"这种最难查的情况。
///
///   所以这里固定用 Asia/Shanghai（项目基准时区，与 Java 侧
///   `TimeZone.setDefault(Asia/Shanghai)` 一致），
///   并且把时刻设成当天**正午**，远离任何时区边界。
/// 离线矢量底图的解码契约（Swift 侧）。
///
/// ★ 期望值来自哪里，为什么重要
///
///   `vectors.json` 里 basemap 段的期望值是用 **Python 独立算出来的**
///   （`tools/compute-basemap-truth.py`），不是从任何一方的实现里读出来的。
///
///   如果拿 Java 的结果当期望值，"测试通过"只证明 Swift 与 Java 一致，
///   **不证明两者都解对了** —— 两边完全可能用同样的方式理解错格式。
///   三个实现互相印证，比两个互相印证强得多。
///
/// ★ 为什么不能只比几何数和顶点数
///
///   计数对但坐标错位是完全可能的：差值累加时起点写错、经纬度顺序写反、
///   符号映射差 1 —— 这些都不会改变顶点总数。
///   所以契约里还锁了第一个几何的**前 4 个顶点坐标**，逐点比对。
///
/// 与 Java 侧对应：`tools/VectorTestMap.java` 的 `runBasemap`。**改一边必须改另一边。**
///
/// - Parameter baseDir: 工程根目录（basemap_*.txt 在 assets/ 下）
func runBasemapContract(_ root: [String: Any], _ r: Runner, baseDir: URL) {
    print("\n=== 契约: basemap ===")

    guard let bm = Vec.section(root, "basemap") else {
        r.check("basemap 段存在", false, "契约文件里找不到这一段")
        return
    }

    // iOS 上数据在 App 包里，测试时直接读工程的 assets/ 目录 ——
    // 两处是同一份文件，所以测的就是产品数据。
    let assets = baseDir.appendingPathComponent("assets")
    let waterURL = assets.appendingPathComponent("basemap_water.txt")
    let riverURL = assets.appendingPathComponent("basemap_river.txt")
    let boundaryURL = assets.appendingPathComponent("basemap_boundary.txt")

    for u in [waterURL, riverURL, boundaryURL]
    where !FileManager.default.fileExists(atPath: u.path) {
        r.check("底图数据文件存在", false, "找不到 \(u.path)")
        return
    }

    var map = Basemap()
    let msg = map.load(waterURL: waterURL, riverURL: riverURL, boundaryURL: boundaryURL)
    guard map.loaded else {
        r.check("底图载入", false, msg)
        return
    }
    r.check("底图载入成功", true, msg)

    // ---- 三个图层的几何数与顶点数
    let layers: [(String, String, [Basemap.Shape], Bool)] = [
        ("water", "水系", map.water, true),
        ("river", "河道", map.river, false),
        ("boundary", "区县界", map.boundary, true),
    ]
    for (key, label, shapes, expectClosed) in layers {
        guard let want = Vec.section(bm, key) else { continue }

        let wantShapes = Int(Vec.num(want, "shapes", -1))
        r.check(label + " 几何数", shapes.count == wantShapes,
                "\(shapes.count) 个  期望 \(wantShapes)")

        var vsum = 0
        for s in shapes { vsum += s.lats.count }
        let wantV = Int(Vec.num(want, "vertices", -1))
        r.check(label + " 顶点数", vsum == wantV, "\(vsum) 个  期望 \(wantV)")

        // 闭合标志：河道是折线，其余是闭合面
        let allMatch = shapes.allSatisfy { $0.closed == expectClosed }
        r.check(label + " 闭合标志正确", allMatch,
                expectClosed ? "全部应为闭合面" : "全部应为折线")
    }

    // ---- 总顶点数
    let wantTotal = Int(Vec.num(bm, "totalVertices", -1))
    r.check("顶点总数", map.vertexCount == wantTotal,
            "\(map.vertexCount)  期望 \(wantTotal)")

    // ---- 经纬度外接框
    if let bounds = Vec.section(bm, "bounds") {
        let tol = Vec.num(bounds, "tol", 0.0002)
        let got = map.bounds()
        let pairs: [(String, Double)] = [
            ("minLat", got.minLat), ("minLon", got.minLon),
            ("maxLat", got.maxLat), ("maxLon", got.maxLon),
        ]
        for (name, gotVal) in pairs {
            let wantVal = Vec.num(bounds, name, Double.nan)
            if wantVal.isNaN { continue }
            r.check("外接框 " + name, abs(gotVal - wantVal) < tol,
                    String(format: "%.4f  期望 %.4f ±%.4f", gotVal, wantVal, tol))
        }
    }

    // ---- 逐点核对第一个水体几何
    //
    // 这一步才是真正能抓住"坐标错位"的检查。
    if let sample = Vec.section(bm, "sample"), !map.water.isEmpty {
        let s0 = map.water[0]
        let wantN = Int(Vec.num(sample, "vertexCount", -1))
        r.check("样本顶点数", s0.lats.count == wantN, "\(s0.lats.count)  期望 \(wantN)")

        let wantName = Vec.str(sample, "name", "")
        r.check("样本名称", s0.name == wantName, "'\(s0.name)'  期望 '\(wantName)'")

        let stol = Vec.num(sample, "tol", 0.00002)
        let wantVerts = Vec.ranges(sample, "vertices")
        for i in 0..<min(wantVerts.count, s0.lats.count) {
            let w = wantVerts[i]
            let dLat = abs(Double(s0.lats[i]) - w.0)
            let dLon = abs(Double(s0.lons[i]) - w.1)
            r.check("样本顶点\(i)", dLat < stol && dLon < stol,
                    String(format: "%.5f,%.5f  期望 %.5f,%.5f",
                           Double(s0.lats[i]), Double(s0.lons[i]), w.0, w.1))
        }

        let wantClosed = Vec.bool(sample, "closed", true)
        r.check("样本闭合标志", s0.closed == wantClosed,
                "\(s0.closed)  期望 \(wantClosed)")
    }
}

private func dateOf(_ ymd: String) -> Date? {
    let parts = Vec.parseDateTime(ymd)
    var comps = DateComponents()
    comps.year = parts.year
    comps.month = parts.month
    comps.day = parts.day
    comps.hour = 12          // 正午，远离时区边界
    comps.minute = 0
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "Asia/Shanghai")
        ?? TimeZone(secondsFromGMT: 8 * 3600)!
    return cal.date(from: comps)
}

/// 数据模型的序列化往返测试。
///
/// 严格说这部分**不属于跨语言契约**（`vectors.json` 里没有对应段落），
/// 但它是"数据能不能在两端之间搬"的前提：安卓导出的 JSON 要能被 iOS 读进来，
/// 反之亦然。字段名对不上就会**静默丢数据** —— 导出成功、文件也在，
/// 但重新导入后发现少了几个字段。
///
/// 所以这里做两件事：
///   1. 编码再解码，字段值必须一模一样（自洽性）
///   2. JSON 里必须是契约约定的 snake_case 键名（跨端兼容性）
func runModelRoundTrip(_ r: Runner) {
    print("\n=== 自检: 数据模型序列化往返 ===")

    // ---------------------------------------------------------------- 钓点
    //
    // ★ 必须用 `var` 而不是 `let`。
    //
    //   `FishingSpot` 和 `CatchRecord` 都是 **struct**（值类型），不是 class。
    //   值类型用 `let` 声明后，它的属性就完全不可改了 ——
    //   报错是 `cannot assign to property: 'spot' is a 'let' constant`。
    //
    //   如果它们是 class，`let` 是可以改属性的（引用不变、内容可变），
    //   所以这个坑只在 struct 上出现。移植时容易搞混。
    var spot = FishingSpot()
    spot.id = 42
    spot.name = "朱庄水库·主坝区"
    spot.lat = 37.075
    spot.lon = 114.49
    spot.hasCoord = true
    spot.waterType = FishingSpot.typeReservoir
    spot.status = FishingSpot.statusRestricted
    spot.district = "沙河市"
    spot.fishSpecies = "翘嘴、青鲈、马口"
    spot.lureTypes = "亮片、米诺"
    spot.note = "坝前有落差，注意脚下"
    spot.favorite = true
    spot.confidence = FishingSpot.confField
    spot.seedVersion = 1

    do {
        let data = try JSONEncoder().encode(spot)
        let back = try JSONDecoder().decode(FishingSpot.self, from: data)

        r.check("FishingSpot 名称往返", back.name == spot.name, "'\(back.name)'")
        r.check("FishingSpot 坐标往返",
                abs(back.lat - spot.lat) < 1e-12 && abs(back.lon - spot.lon) < 1e-12,
                String(format: "%.6f, %.6f", back.lat, back.lon))
        r.check("FishingSpot 中文字段往返",
                back.fishSpecies == spot.fishSpecies && back.district == spot.district,
                "鱼种='\(back.fishSpecies)' 区县='\(back.district)'")
        r.check("FishingSpot 布尔与整型往返",
                back.hasCoord == spot.hasCoord
                    && back.favorite == spot.favorite
                    && back.waterType == spot.waterType
                    && back.seedVersion == spot.seedVersion,
                "hasCoord=\(back.hasCoord) favorite=\(back.favorite) "
                    + "waterType=\(back.waterType) seedVersion=\(back.seedVersion)")

        // 键名必须与安卓版导出的 JSON 一致，否则跨端导入会丢字段。
        //
        // ★ 实际格式是 **camelCase**，不是 snake_case。
        //   第一版测试写成 water_type / has_coord / fish_species，全部失败 ——
        //   是测试写错了。真实数据（assets/spots_seed.json）的键名是
        //   waterType / fish / lure / fee 这种形式。
        //
        //   这里只挑几个**映射关系容易搞错**的键来验：
        //     fee     —— Swift 属性叫 feeNote，JSON 键是 fee（显式映射）
        //     source  —— Swift 属性叫 sourceUrl，JSON 键是 source（显式映射）
        //     其余大多是属性名即键名
        let json = String(data: data, encoding: .utf8) ?? ""
        let expectKeys = ["waterType", "hasCoord", "fishSpecies", "lureTypes",
                          "seedVersion", "fee", "source"]
        for key in expectKeys {
            let ok = json.contains("\"\(key)\"")
            r.check("FishingSpot JSON 键 \(key)", ok,
                    ok ? "存在" : "缺失 —— 与安卓版导出的格式不一致，跨端导入会丢这个字段")
        }
        // 反向确认：不该出现 snake_case 的键（出现了说明有地方写错映射）
        for bad in ["water_type", "has_coord", "fish_species"] {
            let present = json.contains("\"\(bad)\"")
            r.check("FishingSpot 不应出现 \(bad)", !present,
                    present ? "出现了 snake_case 键 —— 与安卓格式不一致" : "未出现 ✅")
        }
    } catch {
        r.check("FishingSpot 序列化", false, "异常: \(error)")
    }

    // ---------------------------------------------------------------- 作钓记录
    // 同样是 struct，必须 var（原因见上面钓点那段的说明）
    var rec = CatchRecord()
    rec.id = 7
    rec.spotId = 42
    rec.spotName = "朱庄水库·主坝区"
    rec.time = 1791350710000
    rec.targetFish = "翘嘴"
    rec.caughtFish = "翘嘴、马口"
    rec.count = 5
    rec.sizeNote = "最大 42cm"
    rec.lureUsed = "亮片 7g"
    rec.method = "远投搜索"
    rec.weather = "多云"
    rec.wind = "东南风 2 级"
    rec.waterNote = "微浑"
    rec.pressure = "1013 hPa"
    rec.rating = 4
    rec.lat = 37.075
    rec.lon = 114.49
    rec.hasCoord = true

    do {
        let data = try JSONEncoder().encode(rec)
        let back = try JSONDecoder().decode(CatchRecord.self, from: data)
        r.check("CatchRecord 鱼种与尾数往返",
                back.caughtFish == rec.caughtFish && back.count == rec.count,
                "'\(back.caughtFish)' \(back.count) 尾")
        r.check("CatchRecord 气压水温往返",
                back.pressure == rec.pressure && back.waterNote == rec.waterNote,
                "气压='\(back.pressure)' 水情='\(back.waterNote)'")
        r.check("CatchRecord 时间戳往返", back.time == rec.time, "\(back.time)")
        r.check("CatchRecord 评分往返", back.rating == rec.rating, "\(back.rating)")

        // 键名同样用 camelCase（原因见上面 FishingSpot 那段的说明）
        let json = String(data: data, encoding: .utf8) ?? ""
        for key in ["spotId", "sizeNote", "lureUsed", "waterNote", "hasCoord",
                    "targetFish", "caughtFish"] {
            let ok = json.contains("\"\(key)\"")
            r.check("CatchRecord JSON 键 \(key)", ok,
                    ok ? "存在" : "缺失 —— 与安卓版导出的格式不一致，跨端导入会丢这个字段")
        }
        for bad in ["spot_id", "size_note", "lure_used"] {
            let present = json.contains("\"\(bad)\"")
            r.check("CatchRecord 不应出现 \(bad)", !present,
                    present ? "出现了 snake_case 键 —— 与安卓格式不一致" : "未出现 ✅")
        }
    } catch {
        r.check("CatchRecord 序列化", false, "异常: \(error)")
    }
}
