import UIKit

/// 主界面（第一版：验证版）。
///
/// ## 这一版刻意做得"很素"，是故意的
///
/// iOS 这条路最大的风险不是写不出界面，而是**开发者看不见界面**。
/// 如果一上来就照安卓版做完整 UI，会掉进一个很熟悉的坑：
/// 代码写了一大堆、GitHub Actions 构建成功、装上手机发现是错的，
/// 然后每一轮都要用户截图、描述、来回传话，改一次等一轮 CI。
///
/// 所以第一版的唯一目标是**把反馈闭环跑通**：
///
///   1. 能在 GitHub Actions 上编译出 IPA
///   2. 能装到用户的 iPhone 8 Plus 上并启动
///   3. 预置的 73 个钓点能读出来（验证资源打包 + JSON 解析）
///   4. 屏幕上直接显示关键环境信息（屏幕尺寸、安全区）—— 用户截一张图我就能确认布局前提对不对
///   5. 诊断文件能通过「文件」App 导出
///
/// 这五件事都验证过了，再往上堆界面才有意义。否则是在流沙上盖楼。
///
/// ## 关于屏幕尺寸为什么值得单独显示
///
/// 用户的设备是 iPhone 8 Plus：**16:9、无刘海、底部无手势条**。
/// 而安卓版那个界面是按 20:9 的长屏调的，纵向空间多得多。
/// 同一套内容搬到 16:9 上，要么滚动更多、要么得压缩排版 ——
/// 这是移植时第一个会撞上的问题，所以先把实测数字摆出来。
final class MainViewController: UIViewController {

    private let scrollView = UIScrollView()
    private let contentStack = UIStackView()

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Palette.bg
        buildUI()
        loadSeedAndReport()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // 布局稳定后再跑一次诊断，这时拿到的才是最终尺寸
        Diag.attach(to: view, label: "主界面显示完成")
    }

    // MARK: - 界面构建

    private func buildUI() {
        // ---- 顶部标题栏
        let titleLabel = UILabel()
        titleLabel.text = "邢台路亚"
        titleLabel.font = .systemFont(ofSize: 22, weight: .bold)
        titleLabel.textColor = Palette.text

        let diagButton = UIButton(type: .system)
        diagButton.setTitle("诊断", for: .normal)
        diagButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
        diagButton.setTitleColor(Palette.accent, for: .normal)
        diagButton.addTarget(self, action: #selector(showDiagnostics), for: .touchUpInside)

        let header = UIStackView(arrangedSubviews: [titleLabel, UIView(), diagButton])
        header.axis = .horizontal
        header.alignment = .center

        // ---- 内容区：可滚动，避免小屏上内容被挤掉
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        contentStack.axis = .vertical
        contentStack.spacing = 12
        contentStack.translatesAutoresizingMaskIntoConstraints = false

        scrollView.addSubview(contentStack)
        view.addSubview(header)
        view.addSubview(scrollView)

        header.translatesAutoresizingMaskIntoConstraints = false

        // ---- 约束
        //
        // 这里用 safeAreaLayoutGuide 而不是写死数值：
        // iPhone 8 Plus 的顶部安全区是 20pt（状态栏），底部是 0（Home 键机型没有手势条）。
        // 写死数字在新机型上就会错位，交给系统算最稳。
        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: guide.topAnchor, constant: 8),
            header.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 16),
            header.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),

            scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 12),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            contentStack.topAnchor.constraint(equalTo: scrollView.topAnchor),
            contentStack.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor, constant: 16),
            contentStack.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor, constant: -16),
            contentStack.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor, constant: -16),
            contentStack.widthAnchor.constraint(equalTo: scrollView.widthAnchor, constant: -32),
        ])

        // ---- 环境信息卡（这一版的主要目的就是把这些数字暴露出来）
        let screen = UIScreen.main.bounds
        let insets = view.safeAreaInsets
        contentStack.addArrangedSubview(card(title: "运行环境", rows: [
            ("设备", Diag.deviceModel()),
            ("系统", "iOS \(UIDevice.current.systemVersion)"),
            ("屏幕", String(format: "%.0f × %.0f pt", screen.width, screen.height)),
            ("像素", String(format: "%.0f × %.0f (@%.0fx)", screen.width * UIScreen.main.scale,
                          screen.height * UIScreen.main.scale, UIScreen.main.scale)),
            ("屏幕比例", String(format: "%.3f（16:9 = 1.778，20:9 = 2.222）", screen.height / screen.width)),
            ("安全区", String(format: "上 %.0f / 下 %.0f", insets.top, insets.bottom)),
        ]))

        // ---- 数据加载结果卡
        seedCard = card(title: "预置数据", rows: [("状态", "加载中…")])
        contentStack.addArrangedSubview(seedCard!)

        // ---- 算法自检卡：核心算法是移植过来的，先在真机上验一遍
        contentStack.addArrangedSubview(card(title: "算法自检（真机）", rows: algorithmChecks()))
    }

    private var seedCard: UIView?

    // MARK: - 数据

    /// 读取打包进来的预置钓点，报告读到了什么。
    ///
    /// 这一步同时验证三件事：资源有没有被正确打进 App、
    /// JSON 能不能解析、数据结构跟安卓版是否一致。
    private func loadSeedAndReport() {
        guard let url = Bundle.main.url(forResource: "spots_seed", withExtension: "json") else {
            updateSeedCard(rows: [("状态", "❌ 找不到 spots_seed.json"),
                                  ("说明", "资源没有被正确打包进 App")])
            return
        }

        guard let data = try? Data(contentsOf: url) else {
            updateSeedCard(rows: [("状态", "❌ 文件读取失败")])
            return
        }

        // 先用宽松方式数一下条目，不依赖具体模型字段 ——
        // 模型是并行开发的，这里不该被它阻塞。
        guard let any = try? JSONSerialization.jsonObject(with: data) else {
            updateSeedCard(rows: [("状态", "❌ JSON 解析失败"),
                                  ("文件大小", "\(data.count) 字节")])
            return
        }

        var items: [[String: Any]] = []
        if let arr = any as? [[String: Any]] {
            items = arr
        } else if let dict = any as? [String: Any] {
            // 安卓版的 seed 文件可能是 {"spots": [...]} 这种包装形式
            for key in ["spots", "items", "data"] {
                if let arr = dict[key] as? [[String: Any]] {
                    items = arr
                    break
                }
            }
        }

        let districts = Set(items.compactMap { $0["district"] as? String }.filter { !$0.isEmpty })
        let withCoord = items.filter { s in
            let lat = (s["lat"] as? Double) ?? (s["lat"] as? NSNumber)?.doubleValue
            let lon = (s["lon"] as? Double) ?? (s["lon"] as? NSNumber)?.doubleValue
            return lat != nil && lon != nil
        }.count

        var rows: [(String, String)] = [
            ("状态", "✅ 读取成功"),
            ("钓点总数", "\(items.count) 个"),
            ("有坐标", "\(withCoord) 个"),
            ("覆盖区县", "\(districts.count) 个"),
            ("文件大小", String(format: "%.1f KB", Double(data.count) / 1024)),
        ]
        if let first = items.first, let name = first["name"] as? String {
            rows.append(("样例", name))
        }
        updateSeedCard(rows: rows)

        // 同时写一份进诊断文件，方便排查
        Diag.recordCrash("预置数据读取: \(items.count) 个钓点, \(withCoord) 个有坐标, \(districts.count) 个区县")
    }

    private func updateSeedCard(rows: [(String, String)]) {
        guard let old = seedCard, let idx = contentStack.arrangedSubviews.firstIndex(of: old) else {
            return
        }
        contentStack.removeArrangedSubview(old)
        old.removeFromSuperview()
        let fresh = card(title: "预置数据", rows: rows)
        contentStack.insertArrangedSubview(fresh, at: idx)
        seedCard = fresh
    }

    // MARK: - 算法自检

    /// 在真机上跑一遍核心算法。
    ///
    /// 这些算法在电脑上已经跑过 70 项契约测试（`ios/swift-test.ps1`），
    /// 但那是 Windows 的 Swift 运行时。**真机上的结果必须一致** ——
    /// 尤其是 `SunMoon` 那种依赖大量浮点三角函数的计算，
    /// 不同架构（x86_64 vs arm64）理论上可能有末位差异。
    /// 这里挑几个有明确期望值的点验一遍。
    private func algorithmChecks() -> [(String, String)] {
        var rows: [(String, String)] = []

        // 邢台市襄都区
        let lat = 37.075, lon = 114.49

        // 1) 坐标转换：偏移量应在合理区间（实测约 532 米）
        let gcj = CoordinateTransform.wgs84ToGcj02(lat, lon)
        let offset = CoordinateTransform.distanceMeters(lat, lon, gcj.lat, gcj.lon)
        let offsetOK = offset > 200 && offset < 900
        rows.append(("坐标偏移量", String(format: "%.0f 米 %@", offset, offsetOK ? "✅" : "❌ 超出 200~900")))

        // 2) 往返误差应该极小
        let back = CoordinateTransform.gcj02ToWgs84(gcj.lat, gcj.lon)
        let rtErr = CoordinateTransform.distanceMeters(lat, lon, back.lat, back.lon)
        rows.append(("往返误差", String(format: "%.2f 米 %@", rtErr, rtErr < 5 ? "✅" : "❌")))

        // 3) 日出日落：夏至约 04:59 / 19:44
        let ss = SunMoon.sunriseSunset(2026, 6, 21, lat, lon, 8)
        let sunriseOK = ss.sunrise > 4.95 && ss.sunrise < 5.05
        rows.append(("夏至日出", String(format: "%@ %@", SunMoon.hourToTime(ss.sunrise),
                                      sunriseOK ? "✅" : "❌ 不在 04:57~05:03")))

        // 4) 月龄与鱼口评分
        let age = SunMoon.moonAge(date: Date())
        rows.append(("今日月龄", String(format: "%.1f 天 · %@ %d 分",
                                      age, SunMoon.moonPhaseEmoji(age),
                                      SunMoon.fishActivityScore(age))))

        // 5) 投影比例：两个方向的地面米数必须相等（否则地图会拉伸变形）
        let vp = GeoViewport(centerLat: 37.225, centerLon: 114.775, widthPx: 390, heightPx: 600, zoom: 1.0)
        let ratioOK = abs(vp.pxPerLonDeg() / vp.pxPerLatDeg() - cos(37.225 * .pi / 180)) < 1e-9
        rows.append(("投影比例", String(format: "lon/lat = %.6f %@",
                                      vp.pxPerLonDeg() / vp.pxPerLatDeg(),
                                      ratioOK ? "✅ 与 cos(纬度) 一致" : "❌ 不一致，地图会变形")))

        return rows
    }

    // MARK: - 诊断

    @objc private func showDiagnostics() {
        let text = Diag.report(for: view, label: "诊断页请求")
        Diag.write(text)

        let vc = UIViewController()
        vc.view.backgroundColor = Palette.bg
        vc.title = "布局诊断"

        let tv = UITextView()
        tv.text = text
        tv.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        tv.textColor = Palette.textDim
        tv.backgroundColor = Palette.bg
        tv.isEditable = false
        tv.translatesAutoresizingMaskIntoConstraints = false
        vc.view.addSubview(tv)

        let hint = UILabel()
        hint.text = "完整内容已存到「文件」App → 我的 iPhone → XtLure → \(Diag.fileName)"
        hint.font = .systemFont(ofSize: 11)
        hint.textColor = Palette.accent
        hint.numberOfLines = 0
        hint.translatesAutoresizingMaskIntoConstraints = false
        vc.view.addSubview(hint)

        NSLayoutConstraint.activate([
            hint.topAnchor.constraint(equalTo: vc.view.safeAreaLayoutGuide.topAnchor, constant: 8),
            hint.leadingAnchor.constraint(equalTo: vc.view.leadingAnchor, constant: 12),
            hint.trailingAnchor.constraint(equalTo: vc.view.trailingAnchor, constant: -12),
            tv.topAnchor.constraint(equalTo: hint.bottomAnchor, constant: 8),
            tv.leadingAnchor.constraint(equalTo: vc.view.leadingAnchor),
            tv.trailingAnchor.constraint(equalTo: vc.view.trailingAnchor),
            tv.bottomAnchor.constraint(equalTo: vc.view.bottomAnchor),
        ])

        navigationController?.pushViewController(vc, animated: true)
    }

    // MARK: - 小工具

    /// 造一张信息卡：标题 + 若干"键 值"行
    private func card(title: String, rows: [(String, String)]) -> UIView {
        let box = UIView()
        box.backgroundColor = Palette.card
        box.layer.cornerRadius = 12

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(stack)

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        titleLabel.textColor = Palette.accent
        stack.addArrangedSubview(titleLabel)

        for (k, v) in rows {
            let key = UILabel()
            key.text = k
            key.font = .systemFont(ofSize: 13)
            key.textColor = Palette.textFaint
            key.setContentHuggingPriority(.required, for: .horizontal)

            let val = UILabel()
            val.text = v
            val.font = .systemFont(ofSize: 13)
            val.textColor = Palette.text
            val.numberOfLines = 0

            let row = UIStackView(arrangedSubviews: [key, val])
            row.axis = .horizontal
            row.spacing = 10
            row.alignment = .top
            stack.addArrangedSubview(row)
        }

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: box.topAnchor, constant: 14),
            stack.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: box.trailingAnchor, constant: -14),
            stack.bottomAnchor.constraint(equalTo: box.bottomAnchor, constant: -14),
        ])
        return box
    }
}
