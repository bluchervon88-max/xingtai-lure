# =============================================================================
#  跑全部离线自检（纯 JVM，不需要安卓设备）
#
#  为什么需要：本机没有安卓设备/模拟器，装机前只能靠这些测试兜住运行时错误。
#  覆盖范围：
#    CoreTest —— 坐标转换精度、日出日落、太阳高度角、月相
#    SeedTest —— 预置数据解析、字段落位、搜索、导出/导入往返
#
#  用法: powershell -ExecutionPolicy Bypass -File run-tests.ps1
# =============================================================================

$ErrorActionPreference = 'Stop'

try { chcp 65001 | Out-Null } catch { }
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$Jdk  = 'C:\MCLDownload\ext\jre-v64-220420\jdk17'
$Plat = 'C:\dev\android\sdk\platforms\android-34\android.jar'
$Proj = 'C:\Users\Administrator\Documents\deepseek-harness\default-workspace\xingtai-lure'

# ★ 切到工程根目录再跑。
#   VectorTest 要读 testdata/vectors.json，它按当前工作目录向上查找；
#   从别处调用这个脚本（比如从 default-workspace）就会找不到契约文件。
Set-Location $Proj

$Src     = Join-Path $Proj 'src'
$Tools   = Join-Path $Proj 'tools'
$OutBase = Join-Path $Proj 'build\tests'

function Head($m) { Write-Host ""; Write-Host "########## $m ##########" -ForegroundColor Cyan }
function Fail($m) { Write-Host ("测试构建失败: " + $m) -ForegroundColor Red; exit 1 }

$JavaExe  = Join-Path $Jdk 'bin\java.exe'
$JavacExe = Join-Path $Jdk 'bin\javac.exe'

# 图标位图校验需要 Python + Pillow（矢量路径校验用 Node，位图必须用 Python）
$Python = 'C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
if (-not (Test-Path $Python)) {
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    $Python = if ($cmd) { $cmd.Source } else { $null }
}

$totalFail = 0

# ---------------------------------------------------------------- CoreTest
Head "CoreTest  核心算法"
$out = Join-Path $OutBase 'core'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

& $JavacExe -encoding UTF-8 -nowarn -d $out -sourcepath $Src (Join-Path $Tools 'CoreTest.java')
if ($LASTEXITCODE -ne 0) { Fail 'CoreTest 编译' }

& $JavaExe '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$out;$Plat" CoreTest
if ($LASTEXITCODE -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- VectorTest
# 跨平台一致性契约（Java 侧）。
# 期望值来自 testdata/vectors.json —— Swift 侧读的是同一份文件。
# 与 CoreTest 的分工：CoreTest 记录实现现在算出多少，VectorTest 断言必须落在契约区间内。
# 移植 iOS 时，VectorTest 是验收标准。
Head "VectorTest  跨平台一致性契约"
$outV = Join-Path $OutBase 'vector'
if (Test-Path $outV) { Remove-Item $outV -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outV | Out-Null

& $JavacExe -encoding UTF-8 -nowarn -d $outV -sourcepath $Src `
    (Join-Path $Tools 'VectorReader.java') (Join-Path $Tools 'VectorTest.java')
if ($LASTEXITCODE -ne 0) { Fail 'VectorTest 编译' }

& $JavaExe '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$outV;$Plat" VectorTest
if ($LASTEXITCODE -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- SeedTest
Head "SeedTest  预置数据与导出导入"
$out2 = Join-Path $OutBase 'seed'
if (Test-Path $out2) { Remove-Item $out2 -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out2 | Out-Null

$srcs = @()
foreach ($pkg in @('model', 'util', 'core', 'data')) {
    $srcs += Get-ChildItem (Join-Path $Src "com\xtlure\app\$pkg") -Recurse -Filter *.java |
        ForEach-Object { $_.FullName }
}
$srcs += Join-Path $Src 'com\xtlure\app\ui\Seed.java'
$srcs += Join-Path $Tools 'SeedTest.java'

& $JavacExe -encoding UTF-8 -nowarn -cp $Plat -d $out2 $srcs
if ($LASTEXITCODE -ne 0) { Fail 'SeedTest 编译' }

Push-Location $Proj
& $JavaExe '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $out2 SeedTest 'assets/spots_seed.json'
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- ThemeTest
Head "ThemeTest  两套配色可读性审计"
$out3 = Join-Path $OutBase 'theme'
if (Test-Path $out3) { Remove-Item $out3 -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out3 | Out-Null

& $JavacExe -encoding UTF-8 -nowarn -cp $Plat -d $out3 `
    (Join-Path $Src 'com\xtlure\app\util\Ui.java') `
    (Join-Path $Src 'com\xtlure\app\util\Theme.java') `
    (Join-Path $Src 'com\xtlure\app\util\Prefs.java') `
    (Join-Path $Src 'com\xtlure\app\util\AppCtx.java') `
    (Join-Path $Tools 'ThemeTest.java')
if ($LASTEXITCODE -ne 0) { Fail 'ThemeTest 编译' }

& $JavaExe '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$out3;$Plat" ThemeTest
if ($LASTEXITCODE -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- 地图校验
# 投影比例与聚类逻辑是"地图比例/缩放"问题的回归测试。
# 这两件事在截图上看不出对错（差 25% 的横向拉伸肉眼判断不了），
# 但算出来一目了然 —— 本次排查中被陈旧读数误导过好几轮，所以必须固化成断言。
Head "verify-projection  地图投影比例"
node (Join-Path $Tools 'verify-projection.js')
if ($LASTEXITCODE -ne 0) { $totalFail++ }

Head "test-cluster  聚类算法"
node (Join-Path $Tools 'test-cluster.js')
if ($LASTEXITCODE -ne 0) { $totalFail++ }

Head "verify-codec  底图顶点编码往返"
node (Join-Path $Tools 'verify-codec.js')
if ($LASTEXITCODE -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- 数据校验
Head "validate-seed  预置数据静态校验"
node (Join-Path $Tools 'validate-seed.js') (Join-Path $Proj 'assets\spots_seed.json')
if ($LASTEXITCODE -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- 布局参数检查
#
# ★ 这条是踩坑后加的：横向 LinearLayout 里 weight 只分配宽度，
#   height 传 0 会让控件高度变成 0 —— 控件完全不可见，而且因为不可见，
#   uiautomator dump 里也不出现，排查极难。「记一次作钓」表单 20 个控件
#   曾全部隐身，只剩标签和按钮。
Head "check-layoutparams  布局参数检查"
node (Join-Path $Tools 'check-layoutparams.js')
if ($LASTEXITCODE -ne 0) { $totalFail++ }

# ---------------------------------------------------------------- 图标校验
#
# ★ 图标已从矢量改为位图（2026-10 改版），检查方式随之更换。
#   原来检查 res/drawable/ic_launcher_foreground.xml 的路径语法与安全区；
#   换成位图素材后那个文件已被删除，但这里仍在检查它，
#   于是自检长期报"1 项未通过" —— 是**检查本身失效**，不是代码有问题。
#
#   位图图标要查的东西完全不同，见 tools/validate-icon-assets.py：
#   各密度尺寸、透明背景、内容是否落在安全区、是否居中、描述文件引用是否正确。
Head "validate-icon-assets  自适应图标位图校验"
if ($Python) {
    & $Python (Join-Path $Tools 'validate-icon-assets.py')
    if ($LASTEXITCODE -ne 0) { $totalFail++ }
} else {
    Write-Host "  跳过：找不到 Python（图标位图校验需要 Pillow）" -ForegroundColor Yellow
}

# 背景图层仍是矢量（纯色满幅），继续用矢量校验器查路径语法。
# 注意：这里只传 background，不再传已删除的 foreground。
if (Test-Path (Join-Path $Proj 'res\drawable\ic_launcher_background.xml')) {
    Head "validate-vector  背景图层路径校验"
    node (Join-Path $Tools 'validate-vector.js') `
        (Join-Path $Proj 'res\drawable\ic_launcher_background.xml')
    if ($LASTEXITCODE -ne 0) { $totalFail++ }
}

# ---------------------------------------------------------------- iOS 工程结构
#
# ★ 手写的 pbxproj 必须校验。
#   pbxproj 靠 24 位 ID 互相引用，写错了 Xcode 可能**静默漏编某个文件** ——
#   构建成功、产物正常，但那部分代码根本没进去，运行时才崩。
#   而且这个工程文件是在 Windows 上手写的（没有 Xcode 可用），更需要自动检查。
Head "validate-pbxproj  iOS 工程结构"
if ($Python) {
    & $Python (Join-Path $Tools 'validate-pbxproj.py')
    if ($LASTEXITCODE -ne 0) { $totalFail++ }
} else {
    Write-Host "  跳过：找不到 Python" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 结论
Head "结论"
if ($totalFail -eq 0) {
    Write-Host "  全部自检通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("  有 " + $totalFail + " 项自检未通过") -ForegroundColor Red
    exit 1
}
