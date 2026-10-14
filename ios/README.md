# iOS 版构建与安装指南

> 目标设备：**iPhone 8 Plus / iOS 16.7.14 / 16:9 屏 / 有 Home 键**
>
> 开发环境：**Windows**（没有 Mac，也不需要买 Mac）

---

## 一、整体思路

iOS 装 App 必须签名，而签名要 Apple 的证书。本项目是自用工具，走**免费路线**，
代价是签名只能维持 7 天。解决办法是把工作拆成三段：

```
① 编译          GitHub Actions 的 macOS runner（免费）
                产物：未签名的 IPA
                     ↓
② 签名 + 安装    SideStore（装在你的 iPhone 上，用你自己的免费 Apple ID）
                     ↓
③ 续签          SideStore 在后台自动完成，不需要再碰电脑
```

**这样做的好处**：不需要 Mac、不需要 $99、不需要把 Apple ID 密码交给任何人。

---

## 二、为什么必须用公开仓库

GitHub Actions 的 macOS runner 计费规则：

| 仓库类型 | macOS runner 额度 |
|---|---|
| **公开仓库** | **完全免费、无限时长** |
| 私有仓库 | 2000 分钟/月 ÷ **10 倍倍率** = 实际约 **200 分钟**（≈20 次构建） |

开发期一天要跑好几次构建，**私有仓库的额度不够用**。

`build-ios.yml` 因此刻意**不接触任何签名材料**，仓库里可以放心公开。
但公开前必须确认：**安卓签名密钥库不能进仓库** —— 见第六节。

---

## 三、你需要做的（一次性准备）

### 1. 把仓库推到 GitHub（公开）

```powershell
cd C:\Users\Administrator\Documents\deepseek-harness\default-workspace\xingtai-lure

git init
git add .
git commit -m "初始提交"
git branch -M main
git remote add origin https://github.com/<你的用户名>/xingtai-lure.git
git push -u origin main
```

★ **推送前先看第六节**，确认密钥库没被提交。

### 2. 等待第一次构建

推上去之后，GitHub 会自动跑 `Build iOS IPA` 工作流：

```
Actions 页面 → Build iOS IPA (unsigned) → 最近的运行
```

两个 job 会依次执行：

| Job | 在哪跑 | 干什么 |
|---|---|---|
| `contract-test` | Linux（便宜） | 先验逻辑层的 70 项契约测试，挂了就不浪费 macOS 额度 |
| `build-ipa` | macOS（贵） | 编译 + 打包成未签名 IPA |

跑完在运行页面底部下载产物 **`XtLure-unsigned-ipa`**，里面是 `XtLure-unsigned.ipa`。

### 3. 在 iPhone 上装 SideStore

按官方文档操作：<https://docs.sidestore.io/docs/installation/prerequisites>

要点：
- **安装阶段需要一台电脑**（Windows 也行），之后就不需要了
- 用一个**专门的小号 Apple ID**，不要用主力账号
  （第三方签名工具都有触发 Apple ID 风控的可能，官方 FAQ 里也提到了这点）
- 免费 Apple ID 的限制：**同时只能装 3 个 App**（含 SideStore 自己），
  **一周内最多 10 个不同的 App**

### 4. 把 IPA 装进去

几种方式，任选：

- 在 iPhone 上把 IPA 存进「文件」App，然后用 SideStore 打开它
- 把 IPA 发到手机上（微信/网盘/AirDrop 都行），用 SideStore 打开
- SideStore 支持从 URL 安装

装好后：**SideStore 会在后台自动续签**，正常情况下你不需要再管它。

---

## 四、开发期怎么迭代

改动 iOS 代码后：

```powershell
git add .
git commit -m "改了什么"
git push
```

去 Actions 页面等构建（约 3~6 分钟），下载新 IPA，覆盖安装。
**数据不会丢**（SideStore 覆盖安装保留 App 数据）。

也可以手动触发：Actions → Build iOS IPA → Run workflow。

---

## 五、我看不见你的屏幕 —— 所以 App 会自己写诊断

这是本项目 iOS 开发最关键的一个设计问题。

安卓版开发时我有这些工具：

| 能力 | 安卓 | iOS |
|---|---|---|
| 截图 | `adb shell screencap` | ✗ |
| 界面层级 | `uiautomator dump` | ✗ |
| 模拟点击 | `adb shell input tap` | ✗ |
| 运行日志 | `adb logcat` | ✗ |

安卓版有好几个 bug 是靠 `uiautomator dump` 才定位的。最典型的一个：
**「记录作钓」表单里 10 个输入框全部不可见** —— 代码看着没问题、编译也过了，
但 dump 出来是 `EditText 宽 931px 高 0px`，直接指向了布局参数的写法错误。

**iOS 上没有这套工具，所以 App 必须自己把同样的信息写出来。**

### 怎么取诊断数据（不需要电脑、不需要数据线）

App 里内置了诊断功能，界面上有个「**诊断**」按钮。数据会写到文档目录，
而 `Info.plist` 里开了文件共享，所以：

```
iPhone →「文件」App →「我的 iPhone」→ XtLure → layout-dump.txt
```

把这个文件发给我即可。也可以直接点「诊断」按钮截图。

### 诊断文件里有什么

```
==== 布局实测 2026-10-07 18:30:12 ====
设备: iPhone10,2 (iPhone 8 Plus) / iOS 16.7.14
屏幕: 414.0 x 736.0 pt  (scale 3.0, 原生 1242x2208)
安全区: top=20.0 bottom=0.0

---- 视图层级 ----
UIWindow 414.0x736.0 @ (0.0, 0.0)
  UIScrollView 414.0x692.0 @ (0.0, 44.0) [可滚动 内容高 1204]
    UILabel 374.0x20.0 @ (20.0, 12.0) '邢台路亚'
    ...
---- 共 87 个节点 ----

⚠ 发现 2 个「有内容但尺寸为 0」的控件（在界面上看不见）：
   UILabel 0.0x0.0 @ (0.0, 0.0) '距离'
```

最后那个「尺寸为 0 的控件」检查就是专门为抓上面说的那类 bug 设计的 ——
**控件存在、代码没报错、但用户看不见**，这是最难自查的一类缺陷。

---

## 六、⚠️ 公开仓库前必须做的事：移出安卓签名密钥库

仓库里有这两个东西，**不能公开**：

```
keystore\xtlure.keystore          安卓签名密钥库
build.ps1 第 236~249 行           密码 xtlure2026 硬编码在里面
```

**为什么不能公开**：别人拿到密钥库 + 密码，就能签出冒充你的 App，
覆盖安装到你或朋友的手机上。

`.gitignore` 里已经排除了 `keystore/`，但**推送前一定要再确认一次**：

```powershell
git status --porcelain | Select-String keystore
# 应该没有任何输出
```

如果密钥库已经被提交过，光加 .gitignore 是没用的（历史里还在），需要：

```powershell
git rm --cached keystore/xtlure.keystore
git commit -m "移出签名密钥库"
```

### 密钥库要自己备份

它丢了或换了，**已装 App 的人必须卸载重装，数据一起丢**。
建议存到网盘或密码管理器里，并记下密码。

---

## 七、这条路线的已知限制

| 限制 | 说明 | 能否解决 |
|---|---|---|
| 7 天过期 | 免费签名只能维持 7 天 | ✅ SideStore 后台自动续签 |
| 同时 3 个 App | 免费 Apple ID 的限制，含 SideStore 自己 | ❌ 除非买 $99/年开发者账号 |
| 一周 10 个 App ID | 装太多新 App 会触发 | ⚠️ 正常开发碰不到 |
| 不能上架 App Store | 免费签名不可能上架 | 本项目的目标也不是上架 |
| **UI 迭代慢** | 我看不见界面，每轮要你导诊断或截图 | ⚠️ 靠内置诊断减轻 |

---

## 八、目录结构

```
ios/
  XtLure.xcodeproj/
    project.pbxproj                    手写的工程文件（Windows 上没法用 Xcode 建）
    xcshareddata/xcschemes/XtLure.xcscheme
                                       CI 靠它找到构建目标
  XtLure/
    AppDelegate.swift                  入口 + 全局崩溃捕获
    SceneDelegate.swift                窗口
    MainViewController.swift           主界面（第一版是验证版）
    Diagnostics.swift                  ★ 诊断模块，替代 adb 的界面 dump
    Palette.swift                      配色（与安卓版一致）
    Info.plist                         含文件共享开关（诊断导出靠它）
    Resources/spots_seed.json          73 个预置钓点
    Core/                              ← 生成物，由 sync-sources.js 同步
  Sources/
    XtLureCore/                        ★ 逻辑层单一来源（能在 Windows 上编译测试）
    VectorTest/                        跨语言契约测试
  sync-sources.js                      把 XtLureCore 同步到 XtLure/Core
  swift-test.ps1                       Windows 上跑契约测试
```

### 为什么源码有两个位置

| 位置 | 用途 |
|---|---|
| `Sources/XtLureCore/` | **单一来源**。不依赖 UIKit，能在 Windows 上用 `swiftc` 编译和测试 |
| `XtLure/Core/` | **生成物**。Xcode 工程按固定相对路径找文件，不认 `XtLureCore` 那种目录 |

**只改 `XtLureCore`**，然后跑 `node ios/sync-sources.js`。
CI 会检查两边是否一致 —— 不一致直接构建失败（防止"测试是绿的但编出来的是旧代码"）。

---

## 九、本地能做的事（Windows）

```powershell
# 跑契约测试（70 项，验证逻辑层与安卓版一致）
powershell -ExecutionPolicy Bypass -File ios\swift-test.ps1

# 同步源码到 Xcode 工程
node ios/sync-sources.js

# 只检查是否需要同步
node ios/sync-sources.js --check
```

**UI 没法在本地验证** —— 这是这条路线的根本代价。
所以界面改动尽量一次做小一点，改完就构建、装、导诊断，别攒一大批再试。
