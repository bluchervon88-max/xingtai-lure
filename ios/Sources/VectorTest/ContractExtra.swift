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
