import Foundation

// 跨平台一致性契约测试（Swift 侧）的入口。
//
// ★ Swift 规定：多文件一起编译时，只有 main.swift 允许写顶层语句。
//   所以测试流程放在这里，helper（Vec / Runner / Contrast）放在 Vec.swift。
//
// 期望值来自 testdata/vectors.json —— 与 Java 侧 VectorTest 读的是同一份文件，
// 输出格式也刻意保持一致，便于直接 diff 两边结果。

let r = Runner()

guard let root = Vec.load() else {
    print("\nPASS=0  FAIL=1")
    exit(1)
}

// ---------------------------------------------------------------- 坐标转换

print("=== 契约: coordinateTransform ===")
let coords = Vec.list(root, "coordinateTransform")
// 向量条数写死是刻意的：往 vectors.json 加向量时必须同时更新这里与 Java 侧，
// 否则新向量可能在某一边被静默忽略。
r.check("向量条数符合预期(5)", coords.count == 5, "\(coords.count) 条")

for c in coords {
    let id = Vec.str(c, "id", "?")
    guard let wgs = Vec.pair(c, "wgs84") else {
        r.check(id, false, "缺少 wgs84")
        continue
    }

    if Vec.has(c, "offsetRange"), let rg = Vec.pair(c, "offsetRange") {
        let gcj = CoordinateTransform.wgs84ToGcj02(wgs.0, wgs.1)
        let off = CoordinateTransform.distanceMeters(wgs.0, wgs.1, gcj.lat, gcj.lon)
        r.inRange(id, off, rg.0, rg.1, "m")
    } else if Vec.has(c, "roundtripMaxMeters") {
        let maxM = Vec.num(c, "roundtripMaxMeters", 5)
        let gcj = CoordinateTransform.wgs84ToGcj02(wgs.0, wgs.1)
        let gBack = CoordinateTransform.gcj02ToWgs84(gcj.lat, gcj.lon)
        let eg = CoordinateTransform.distanceMeters(wgs.0, wgs.1, gBack.lat, gBack.lon)
        r.check(id + "(GCJ-02)", eg < maxM, String(format: "%.2f m < %.1f", eg, maxM))

        let bd = CoordinateTransform.wgs84ToBd09(wgs.0, wgs.1)
        let bBack = CoordinateTransform.bd09ToWgs84(bd.lat, bd.lon)
        let eb = CoordinateTransform.distanceMeters(wgs.0, wgs.1, bBack.lat, bBack.lon)
        r.check(id + "(BD-09)", eb < maxM, String(format: "%.2f m < %.1f", eb, maxM))
    } else if Vec.bool(c, "expectUnchanged", false) {
        let out = CoordinateTransform.wgs84ToGcj02(wgs.0, wgs.1)
        r.check(id, out.lat == wgs.0 && out.lon == wgs.1,
                String(format: "%.4f, %.4f", out.lat, out.lon))
    } else if Vec.has(c, "gcj02Expect"), let exp = Vec.pair(c, "gcj02Expect") {
        let tol = Vec.num(c, "tol", 0.002)
        let got = CoordinateTransform.wgs84ToGcj02(wgs.0, wgs.1)
        let ok = abs(got.lat - exp.0) < tol && abs(got.lon - exp.1) < tol
        r.check(id, ok, String(format: "%.5f, %.5f  期望 %.5f, %.5f ±%.4f",
                               got.lat, got.lon, exp.0, exp.1, tol))
    }
}

// ---------------------------------------------------------------- 距离 / 格式

print("\n=== 契约: distance ===")
for c in Vec.list(root, "distance") {
    let id = Vec.str(c, "id", "?")
    guard let from = Vec.pair(c, "from"), let to = Vec.pair(c, "to"),
          let rg = Vec.pair(c, "range") else {
        r.check(id, false, "向量字段不全")
        continue
    }
    let d = CoordinateTransform.distanceMeters(from.0, from.1, to.0, to.1)
    r.inRange(id, d, rg.0, rg.1, "m")
}

print("\n=== 契约: format ===")
for c in Vec.list(root, "format") {
    let id = Vec.str(c, "id", "?")
    let m = Vec.num(c, "meters", 0)
    let exp = Vec.str(c, "expectStr", "")
    let got = CoordinateTransform.formatDistance(m)
    r.check(id, exp == got, "'\(got)'  期望 '\(exp)'")
}

// ---------------------------------------------------------------- 日月

let sm = Vec.section(root, "sunMoon") ?? [:]
let lat = Vec.num(sm, "lat", 37.075)
let lon = Vec.num(sm, "lon", 114.49)
let tz = Vec.num(sm, "tzOffsetHours", 8)

print("\n=== 契约: sunMoon.sunriseSunset ===")
for c in Vec.list(sm, "sunriseSunset") {
    let id = Vec.str(c, "id", "?")
    let d = Vec.parseDateTime(Vec.str(c, "date", "2026-06-21"))
    let ss = SunMoon.sunriseSunset(d.year, d.month, d.day, lat, lon, tz)
    let len = ss.sunset - ss.sunrise

    if let sr = Vec.pair(c, "sunriseRange") {
        r.inRange(id + " 日出", ss.sunrise, sr.0, sr.1, "")
    }
    if let su = Vec.pair(c, "sunsetRange") {
        r.inRange(id + " 日落", ss.sunset, su.0, su.1, "")
    }
    if let dl = Vec.pair(c, "dayLengthRange") {
        r.inRange(id + " 昼长", len, dl.0, dl.1, "h")
    }
}

print("\n=== 契约: sunMoon.solarAltitude ===")
for c in Vec.list(sm, "solarAltitude") {
    let id = Vec.str(c, "id", "?")
    let d = Vec.parseDateTime(Vec.str(c, "date", "2026-06-21"))
    let hour = Vec.num(c, "hour", 12)
    let alt = SunMoon.solarAltitude(d.year, d.month, d.day, hour, lat, lon, tz)
    if Vec.has(c, "expect") {
        let exp = Vec.num(c, "expect", 0)
        let tol = Vec.num(c, "tol", 1.5)
        r.check(id, abs(alt - exp) <= tol,
                String(format: "%.2f°  期望 %.1f ±%.1f", alt, exp, tol))
    } else if Vec.has(c, "max") {
        let maxV = Vec.num(c, "max", 0)
        r.check(id, alt < maxV, String(format: "%.2f° < %.1f", alt, maxV))
    }
}

print("\n=== 契约: sunMoon.solarAzimuth ===")
for c in Vec.list(sm, "solarAzimuth") {
    let id = Vec.str(c, "id", "?")
    let d = Vec.parseDateTime(Vec.str(c, "date", "2026-06-21"))
    let hour = Vec.num(c, "hour", 12)
    let az = SunMoon.solarAzimuth(d.year, d.month, d.day, hour, lat, lon, tz)
    if Vec.has(c, "expect") {
        let exp = Vec.num(c, "expect", 0)
        let tol = Vec.num(c, "tol", 12)
        r.check(id, abs(az - exp) <= tol,
                String(format: "%.1f°  期望 %.1f ±%.1f", az, exp, tol))
    } else if let rg = Vec.pair(c, "range") {
        r.inRange(id, az, rg.0, rg.1, "°")
    }
}

print("\n=== 契约: sunMoon.moonAge ===")
for c in Vec.list(sm, "moonAge") {
    let id = Vec.str(c, "id", "?")
    let d = Vec.parseDateTime(Vec.str(c, "datetime", "2026-01-03T12:00:00"))
    let age = SunMoon.moonAge(year: d.year, month: d.month, day: d.day,
                              hour: d.hour, minute: d.minute)
    if let rg = Vec.pair(c, "range") {
        r.inRange(id, age, rg.0, rg.1, "天")
    } else if Vec.has(c, "eitherRange") {
        let rs = Vec.ranges(c, "eitherRange")
        var ok = false
        var sb = String(format: "age=%.2f 命中 ", age)
        for rr in rs where age >= rr.0 && age <= rr.1 {
            ok = true
            sb += String(format: "[%.1f,%.1f] ", rr.0, rr.1)
        }
        if !ok { sb += "无区间" }
        r.check(id, ok, sb)
    }
}

print("\n=== 契约: sunMoon.fishActivityScore ===")
for c in Vec.list(sm, "fishActivityScore") {
    let id = Vec.str(c, "id", "?")
    let age = Vec.num(c, "moonAge", 0)
    let score = SunMoon.fishActivityScore(age)
    if Vec.has(c, "min") {
        let minV = Vec.num(c, "min", 0)
        r.check(id, Double(score) >= minV, "\(score) 分  >= \(Int(minV))")
    } else if Vec.has(c, "max") {
        let maxV = Vec.num(c, "max", 0)
        r.check(id, Double(score) <= maxV, "\(score) 分  <= \(Int(maxV))")
    }
}

// ---------------------------------------------------------------- 配色对比度

print("\n=== 契约: theme 对比度下限 ===")
if let theme = Vec.section(root, "theme") {
    for scheme in ["dark", "light"] {
        guard let t = Vec.section(theme, scheme) else { continue }

        let minCard = Vec.num(t, "minCardVsBg", 1.05)
        let cardVsBg = Contrast.ratio(Contrast.parseColor(Vec.str(t, "card", "#FFFFFF")),
                                      Contrast.parseColor(Vec.str(t, "bg", "#000000")))
        r.check(scheme + " 卡片与底色有区分", cardVsBg >= minCard,
                String(format: "%.3f >= %.2f", cardVsBg, minCard))

        let minText = Vec.num(t, "minTextOnCard", 4.5)
        let textVsCard = Contrast.ratio(Contrast.parseColor(Vec.str(t, "text", "#FFFFFF")),
                                        Contrast.parseColor(Vec.str(t, "card", "#000000")))
        r.check(scheme + " 正文对比度达到 AA", textVsCard >= minText,
                String(format: "%.2f >= %.1f", textVsCard, minText))

        let minAccent = Vec.num(t, "minAccentText", 3.0)
        let accentVsCard = Contrast.ratio(Contrast.parseColor(Vec.str(t, "accent", "#00FF00")),
                                          Contrast.parseColor(Vec.str(t, "card", "#000000")))
        r.check(scheme + " 主色文字可辨认", accentVsCard >= minAccent,
                String(format: "%.2f >= %.1f", accentVsCard, minAccent))
    }
}

// ---------------------------------------------------------------- 补充契约
//
// 这两部分原来漏了：地图投影契约（mapProjection 段）和模型序列化往返。
// 详见 ContractExtra.swift 的说明 —— 一句话：契约只测一半等于没测，
// 两边不一致时不会报警。
runMapProjectionContract(root, r)
runClosedSeasonContract(root, r)

// 底图契约要读 assets/ 下的真实数据文件。
// 路径从契约文件的位置反推（Vec.load 是向上找到 testdata/vectors.json），
// 这样不管从哪个目录启动测试都能找到，不依赖当前工作目录。
if let vectorsURL = Vec.findVectorsFile() {
    // testdata/vectors.json -> 上一级就是工程根目录
    let baseDir = vectorsURL.deletingLastPathComponent().deletingLastPathComponent()
    runBasemapContract(root, r, baseDir: baseDir)
} else {
    r.check("底图契约", false, "定位不到工程根目录")
}

runModelRoundTrip(r)

print("\n================================")
print("PASS=\(r.pass)  FAIL=\(r.fail)")
if r.fail > 0 {
    exit(1)
}
