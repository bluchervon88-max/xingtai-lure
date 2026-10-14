import Foundation

// 跨平台一致性契约的读取器与断言工具（Swift 侧）。
//
// 期望值来自 testdata/vectors.json —— 与 Java 侧 VectorTest 读的是**同一份文件**。
//
// ★ Swift 编译规则：多文件一起编译时，只有名为 `main.swift` 的文件允许写顶层语句。
//   所以这里只放类型与函数定义，测试流程放在 main.swift。

// MARK: - 极简 JSON 取值 helper
//
// 不引入第三方 JSON 库：Swift 的 JSONSerialization 就够用，
// 而且 Windows 上的 Swift 只有 Foundation 可用。

enum Vec {

    static func load() -> [String: Any]? {
        guard let url = findVectorsFile() else {
            print("  FAIL  找不到 testdata/vectors.json")
            return nil
        }
        do {
            let data = try Data(contentsOf: url)
            let obj = try JSONSerialization.jsonObject(with: data, options: [])
            guard let dict = obj as? [String: Any] else {
                print("  FAIL  vectors.json 顶层不是对象")
                return nil
            }
            return dict
        } catch {
            print("  FAIL  读取 \(url.path) 失败: \(error)")
            return nil
        }
    }

    /// 向上逐级查找 testdata/vectors.json
    static func findVectorsFile() -> URL? {
        var dir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        for _ in 0..<6 {
            let candidate = dir.appendingPathComponent("testdata/vectors.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            let parent = dir.deletingLastPathComponent()
            if parent.path == dir.path { break }
            dir = parent
        }
        return nil
    }

    static func list(_ dict: [String: Any], _ key: String) -> [[String: Any]] {
        guard let arr = dict[key] as? [Any] else { return [] }
        return arr.compactMap { $0 as? [String: Any] }
    }

    static func section(_ dict: [String: Any], _ key: String) -> [String: Any]? {
        return dict[key] as? [String: Any]
    }

    static func list(_ parent: [String: Any]?, _ key: String) -> [[String: Any]] {
        guard let p = parent else { return [] }
        return list(p, key)
    }

    static func has(_ v: [String: Any], _ key: String) -> Bool {
        guard let x = v[key] else { return false }
        return !(x is NSNull)
    }

    static func num(_ v: [String: Any], _ key: String, _ def: Double) -> Double {
        if let n = v[key] as? NSNumber { return n.doubleValue }
        if let d = v[key] as? Double { return d }
        if let i = v[key] as? Int { return Double(i) }
        return def
    }

    static func str(_ v: [String: Any], _ key: String, _ def: String) -> String {
        return (v[key] as? String) ?? def
    }

    static func bool(_ v: [String: Any], _ key: String, _ def: Bool) -> Bool {
        if let b = v[key] as? Bool { return b }
        if let n = v[key] as? NSNumber { return n.boolValue }
        return def
    }

    static func pair(_ v: [String: Any], _ key: String) -> (Double, Double)? {
        guard let arr = v[key] as? [Any], arr.count >= 2 else { return nil }
        guard let a = toDouble(arr[0]), let b = toDouble(arr[1]) else { return nil }
        return (a, b)
    }

    /// 取 [[a,b],[c,d]] —— "命中任一区间即可"
    static func ranges(_ v: [String: Any], _ key: String) -> [(Double, Double)] {
        guard let outer = v[key] as? [Any] else { return [] }
        return outer.compactMap { item in
            guard let inner = item as? [Any], inner.count >= 2,
                  let a = toDouble(inner[0]), let b = toDouble(inner[1]) else { return nil }
            return (a, b)
        }
    }

    static func toDouble(_ o: Any) -> Double? {
        if let d = o as? Double { return d }
        if let i = o as? Int { return Double(i) }
        if let n = o as? NSNumber { return n.doubleValue }
        return nil
    }

    /// 把 "2026-06-21" 或 "2026-01-19T12:00:00" 拆成 (年, 月, 日, 时, 分)
    static func parseDateTime(_ s: String)
        -> (year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        var datePart = s
        var hour = 0
        var minute = 0
        if let t = s.firstIndex(of: "T") {
            datePart = String(s[s.startIndex..<t])
            let timePart = String(s[s.index(after: t)...])
            let hms = timePart.split(separator: ":")
            if hms.count >= 2 {
                hour = Int(hms[0]) ?? 0
                minute = Int(hms[1]) ?? 0
            }
        }
        let ymd = datePart.split(separator: "-")
        let y = ymd.count > 0 ? (Int(ymd[0]) ?? 2000) : 2000
        let m = ymd.count > 1 ? (Int(ymd[1]) ?? 1) : 1
        let d = ymd.count > 2 ? (Int(ymd[2]) ?? 1) : 1
        return (y, m, d, hour, minute)
    }
}

// MARK: - 断言

final class Runner {
    var pass = 0
    var fail = 0

    func check(_ name: String, _ ok: Bool, _ detail: String) {
        print((ok ? "  PASS  " : "  FAIL  ") + name + "   " + detail)
        if ok { pass += 1 } else { fail += 1 }
    }

    /// 区间断言（含端点）
    func inRange(_ name: String, _ value: Double, _ lo: Double, _ hi: Double, _ unit: String) {
        let ok = value >= lo && value <= hi
        check(name, ok, String(format: "%.4f%@  期望 [%.3f, %.3f]", value, unit, lo, hi))
    }
}

// MARK: - 颜色对比度（纯数学，同样需要跨平台一致）

enum Contrast {

    static func parseColor(_ hex: String) -> (Double, Double, Double) {
        var s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        if s.count == 8 { s = String(s.dropFirst(2)) }   // 去掉 alpha
        let r = Double(Int(s.prefix(2), radix: 16) ?? 0)
        let g = Double(Int(s.dropFirst(2).prefix(2), radix: 16) ?? 0)
        let b = Double(Int(s.dropFirst(4).prefix(2), radix: 16) ?? 0)
        return (r, g, b)
    }

    static func relLuminance(_ c: (Double, Double, Double)) -> Double {
        func f(_ v: Double) -> Double {
            let x = v / 255.0
            return x <= 0.03928 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * f(c.0) + 0.7152 * f(c.1) + 0.0722 * f(c.2)
    }

    static func ratio(_ a: (Double, Double, Double), _ b: (Double, Double, Double)) -> Double {
        let la = relLuminance(a)
        let lb = relLuminance(b)
        let hi = max(la, lb)
        let lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
