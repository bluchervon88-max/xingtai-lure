import Foundation

/// WGS-84 <-> GCJ-02 <-> BD-09 坐标转换。
///
/// 手机 GPS 拿到的是 WGS-84 原始坐标，国内地图（高德/腾讯）使用 GCJ-02（火星坐标），
/// 百度使用 BD-09。把点标到地图上之前必须转换，否则会有几百米偏移。
///
/// ★ 这是 Android 版 `CoordinateTransform.java` 的**忠实移植**。
///   这里的常数与公式必须与 Java 版逐字一致 —— 期望值来自 testdata/vectors.json，
///   两边读同一份契约。改动任何一边都要同步另一边，并跑 run-tests.ps1 与 swift-test.ps1。
///
///   特别注意 `earthRadius`：Java 版用 6371000.0（不是 WGS-84 的 6378137，也不是
///   平均半径 6371008.8）。移植时我一开始用了 6371008.8，同一个测试向量算出 888 米
///   而 Java 是 887 米 —— 差 0.3% 就能让契约测试失败。
public enum CoordinateTransform {

    private static let PI = 3.1415926535897932384626

    /// GCJ-02 长半轴
    private static let A = 6378245.0
    /// GCJ-02 偏心率平方
    private static let EE = 0.00669342162296594323

    /// 百度坐标额外偏移量
    private static let X_PI = PI * 3000.0 / 180.0

    /// 地球半径（米）。与 Java 版保持一致，不要"顺手改成更精确的值"。
    private static let EARTH_RADIUS = 6371000.0

    // MARK: - 境外判定

    /// 是否在中国境外（境外不做偏移）
    public static func outOfChina(_ lat: Double, _ lon: Double) -> Bool {
        if lon < 72.004 || lon > 137.8347 {
            return true
        }
        return lat < 0.8293 || lat > 55.8271
    }

    // MARK: - 偏移量计算

    private static func transformLat(_ x: Double, _ y: Double) -> Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
            + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private static func transformLon(_ x: Double, _ y: Double) -> Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
            + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    // MARK: - 坐标转换

    /// WGS-84 -> GCJ-02
    public static func wgs84ToGcj02(_ lat: Double, _ lon: Double) -> (lat: Double, lon: Double) {
        if outOfChina(lat, lon) {
            return (lat, lon)
        }
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        let radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        let sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat, lon + dLon)
    }

    /// GCJ-02 -> WGS-84
    public static func gcj02ToWgs84(_ lat: Double, _ lon: Double) -> (lat: Double, lon: Double) {
        if outOfChina(lat, lon) {
            return (lat, lon)
        }
        let g = wgs84ToGcj02(lat, lon)
        return (lat * 2 - g.lat, lon * 2 - g.lon)
    }

    /// GCJ-02 -> BD-09
    public static func gcj02ToBd09(_ lat: Double, _ lon: Double) -> (lat: Double, lon: Double) {
        let z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
        let theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
        return (z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    /// BD-09 -> GCJ-02
    public static func bd09ToGcj02(_ lat: Double, _ lon: Double) -> (lat: Double, lon: Double) {
        let x = lon - 0.0065
        let y = lat - 0.006
        let z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        let theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return (z * sin(theta), z * cos(theta))
    }

    /// WGS-84 -> BD-09
    public static func wgs84ToBd09(_ lat: Double, _ lon: Double) -> (lat: Double, lon: Double) {
        let gcj = wgs84ToGcj02(lat, lon)
        return gcj02ToBd09(gcj.lat, gcj.lon)
    }

    /// BD-09 -> WGS-84
    public static func bd09ToWgs84(_ lat: Double, _ lon: Double) -> (lat: Double, lon: Double) {
        let gcj = bd09ToGcj02(lat, lon)
        return gcj02ToWgs84(gcj.lat, gcj.lon)
    }

    // MARK: - 距离

    /// 两点球面距离（米），Haversine
    public static func distanceMeters(_ lat1: Double, _ lon1: Double,
                                      _ lat2: Double, _ lon2: Double) -> Double {
        let dLat = (lat2 - lat1) * PI / 180.0
        let dLon = (lon2 - lon1) * PI / 180.0
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0)
            * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // MARK: - 格式化

    /// 人类可读距离。
    ///
    /// 注意 Java 版用 `(int) Math.round(meters)` —— 是**四舍五入**；`String.format("%.1f")`
    /// 用的是 HALF_UP。Swift 的 `String(format:)` 走 C 的 printf 语义（同样是 HALF_UP 的
    /// 常见实现），但 `rounded()` 默认是 .toNearestOrAwayFromZero，与 Java 的 Math.round
    /// （floor(x+0.5)，即对负数向正无穷取整）在负值上不同。距离不会是负数，此处安全。
    public static func formatDistance(_ meters: Double) -> String {
        if meters < 1000 {
            return "\(Int(meters.rounded())) m"
        }
        return String(format: "%.1f km", meters / 1000.0)
    }

    /// 十进制度 -> 度分秒，例如 37°04'12.3"N
    public static func toDms(_ value: Double, isLat: Bool) -> String {
        let hemi: Character
        if isLat {
            hemi = value >= 0 ? "N" : "S"
        } else {
            hemi = value >= 0 ? "E" : "W"
        }
        let absV = abs(value)
        let deg = Int(absV)
        let min = Int((absV - Double(deg)) * 60)
        let sec = ((absV - Double(deg)) * 60 - Double(min)) * 60
        return String(format: "%d°%02d'%04.1f\"%C", deg, min, sec, String(hemi))
    }
}
