# =============================================================================
#  邢台路亚 App —— 无 Gradle 手工构建流水线
#
#  为什么不用 Gradle：本机没有 Android Studio / Gradle / Maven 依赖解析能力。
#  这条流水线只用 Android SDK 自带的命令行工具，把纯 Java 源码打成签名 APK：
#
#    aapt2 compile  资源 -> 扁平化中间产物
#    aapt2 link     生成 base APK + R.java
#    javac          编译 Java（无任何第三方依赖，只对 android.jar）
#    d8             class -> classes.dex
#    zip            塞 dex 与 assets
#    zipalign       4 字节对齐
#    apksigner      签名
#
#  用法：  powershell -ExecutionPolicy Bypass -File build.ps1
#          powershell -ExecutionPolicy Bypass -File build.ps1 -Release
#          powershell -ExecutionPolicy Bypass -File build.ps1 -Diagnostic
#
#  -Diagnostic  生成一个临时开启 android:debuggable 的包，输出到
#               dist/diagnostic.apk，用于在真机上读应用私有数据
#               （run-as / 查 SQLite / 看 CrashLog）。
#               正式包不受影响：AndroidManifest.xml 不会被修改，
#               只在构建过程中对临时副本动刀，构建结束即丢弃。
# =============================================================================

param(
    [switch]$Release,
    [switch]$SkipClean,
    [switch]$Diagnostic
)

$ErrorActionPreference = 'Stop'

# 控制台切 UTF-8，避免中文日志乱码
try { chcp 65001 | Out-Null } catch { }
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

# ------------------------------------------------------------------ 路径常量
$Jdk    = 'C:\MCLDownload\ext\jre-v64-220420\jdk17'
$Sdk    = 'C:\dev\android\sdk'
$Bt     = Join-Path $Sdk 'build-tools\34.0.0'
$Plat   = Join-Path $Sdk 'platforms\android-34\android.jar'
$Proj   = 'C:\Users\Administrator\Documents\deepseek-harness\default-workspace\xingtai-lure'

$Res        = Join-Path $Proj 'res'
$Assets     = Join-Path $Proj 'assets'
$Src        = Join-Path $Proj 'src'
$Manifest   = Join-Path $Proj 'AndroidManifest.xml'
$Build      = Join-Path $Proj 'build'
$Out        = Join-Path $Proj 'dist'

$ResZip     = Join-Path $Build 'res.zip'
$BaseApk    = Join-Path $Build 'base.apk'
$GenDir     = Join-Path $Build 'gen'
$ClassesDir = Join-Path $Build 'classes'
$DexDir     = Join-Path $Build 'dex'
$DexFile    = Join-Path $DexDir 'classes.dex'
$Unsigned   = Join-Path $Build 'unsigned.apk'
$Aligned    = Join-Path $Build 'aligned.apk'
$Keystore   = Join-Path $Proj 'keystore\xtlure.keystore'

$AppName    = if ($Diagnostic) { 'diagnostic.apk' } elseif ($Release) { 'XingtaiLure-release.apk' } else { 'XingtaiLure-debug.apk' }
$FinalApk   = Join-Path $Out $AppName

$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

function Info($m) { Write-Host ("  " + $m) }
function Head($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor Cyan }
function Die($m)  { Write-Host ("BUILD FAILED: " + $m) -ForegroundColor Red; exit 1 }

function Run($exe, [string[]]$argv, $what) {
    Write-Host ("  > " + $what)
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { Die "$what  (exit $LASTEXITCODE)" }
}

# ------------------------------------------------------------------ 前置检查
Head "检查工具链"
foreach ($f in @(
    @{p = (Join-Path $Jdk 'bin\javac.exe');   n = 'javac'},
    @{p = (Join-Path $Jdk 'bin\keytool.exe'); n = 'keytool'},
    @{p = (Join-Path $Bt 'aapt2.exe');        n = 'aapt2'},
    @{p = (Join-Path $Bt 'd8.bat');           n = 'd8'},
    @{p = (Join-Path $Bt 'zipalign.exe');     n = 'zipalign'},
    @{p = (Join-Path $Bt 'apksigner.bat');    n = 'apksigner'},
    @{p = $Plat;                              n = 'android.jar'}
)) {
    if (-not (Test-Path $f.p)) { Die ("缺少 " + $f.n + " : " + $f.p) }
    Info ("OK  " + $f.n)
}

# ------------------------------------------------------------------ 清理
if (-not $SkipClean) {
    Head "清理"
    foreach ($d in @($Build, $Out)) {
        if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    }
    Info "已清理 build/ 与 dist/"
}
foreach ($d in @($Build, $Out, $GenDir, $ClassesDir, $DexDir)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# ------------------------------------------------------------------ 版本号
# 从 AndroidManifest.xml 读取，manifest 是版本号的唯一来源。
# 以前这里和 manifest 各写一份，结果改了一处、APK 里还是旧版本号。
$manifestText = Get-Content $Manifest -Raw -Encoding UTF8
$verName = ([regex]::Match($manifestText, 'android:versionName\s*=\s*"([^"]+)"')).Groups[1].Value
$verCode = ([regex]::Match($manifestText, 'android:versionCode\s*=\s*"([^"]+)"')).Groups[1].Value
if (-not $verName) { Die "AndroidManifest.xml 里读不到 android:versionName" }
if (-not $verCode) { Die "AndroidManifest.xml 里读不到 android:versionCode" }
if ($verCode -notmatch '^\d+$') { Die "versionCode 必须是整数，当前为「$verCode」" }

$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
Info "版本 $verName (versionCode $verCode)   构建于 $stamp"

# -Diagnostic：把 manifest 复制一份副本，只在副本上加 debuggable。
# 这样正式包永远是干净的，而诊断包可以用 run-as 读私有数据。
$LinkManifest = $Manifest
if ($Diagnostic) {
    # 顶层变量必须用 script: 作用域，否则 if 块内的赋值会在块外丢失
    $script:LinkManifest = Join-Path $Build 'AndroidManifest-diagnostic.xml'
    $diagText = $manifestText -replace '(<application\s)', '$1android:debuggable="true" '
    Set-Content -Path $script:LinkManifest -Value $diagText -Encoding UTF8
    if ($diagText -notmatch 'debuggable') {
        Die "诊断模式：未能把 debuggable 注入 manifest 副本"
    }
    Info "诊断模式：已生成带 debuggable 的 manifest 副本（原文件未改动）"
}

# ------------------------------------------------------------------ 1. 资源编译
Head "1/7  aapt2 compile  资源"
$resFiles = Get-ChildItem -Path $Res -Recurse -File
Info ("待编译资源 " + $resFiles.Count + " 个")
Run (Join-Path $Bt 'aapt2.exe') @('compile', '--dir', $Res, '-o', $ResZip) 'aapt2 compile'

# ------------------------------------------------------------------ 2. 资源链接
Head "2/7  aapt2 link  生成 base APK 与 R.java"
$aapt2Link = @(
    'link',
    '-o', $BaseApk,
    '-I', $Plat,
    '--manifest', $LinkManifest,
    '--java', $GenDir,
    '--min-sdk-version', '21',
    '--target-sdk-version', '34',
    '--version-code', $verCode,
    '--version-name', $verName,
    '--no-version-vectors'
)
if (Test-Path $Assets) {
    $aapt2Link += @('-A', $Assets)
    Info ("已包含 assets: " + ((Get-ChildItem $Assets -Recurse -File).Count) + " 个文件")
}
$aapt2Link += $ResZip
Run (Join-Path $Bt 'aapt2.exe') $aapt2Link 'aapt2 link'

# ------------------------------------------------------------------ 3. 编译 Java
Head "3/7  javac  编译 Java 源码"
$javaFiles = Get-ChildItem -Path $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem -Path $GenDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Info ("源文件 " + $javaFiles.Count + " 个（含生成的 R.java）")

# 源码清单写进参数文件，避免命令行过长（Windows 上限约 32k 字符）。
# 注意：javac 参数文件里反斜杠会被当成转义符，所以路径统一用正斜杠；
# 含空格的路径才加引号（javac 不认 \" 转义，所以留引号的路径必须不含空格）。
$ArgFile = Join-Path $Build 'javac.args'
$argLines = @(
    '-encoding', 'UTF-8',
    '-source', '8',
    '-target', '8',
    '-bootclasspath', ($Plat -replace '\\', '/'),
    '-classpath', ($Plat -replace '\\', '/'),
    '-d', ($ClassesDir -replace '\\', '/'),
    '-nowarn',
    '-Xlint:-options'
)
foreach ($f in $javaFiles) {
    $p = $f -replace '\\', '/'
    if ($p -match '\s') { $p = '"' + $p + '"' }
    $argLines += $p
}
Set-Content -Path $ArgFile -Value $argLines -Encoding ASCII
Run (Join-Path $Jdk 'bin\javac.exe') @('@' + ($ArgFile -replace '\\', '/')) 'javac'

$classCount = (Get-ChildItem -Path $ClassesDir -Recurse -Filter *.class).Count
Info ("生成 class " + $classCount + " 个")
if ($classCount -eq 0) { Die "javac 没有产出任何 class" }

# ------------------------------------------------------------------ 4. dex
Head "4/7  d8  class -> dex"
# 先把 class 打成 jar 再交给 d8：逐个传 class 文件会超出 Windows 命令行长度上限
$ClassesJar = Join-Path $Build 'classes.jar'
Push-Location $ClassesDir
Run (Join-Path $Jdk 'bin\jar.exe') @('cf', $ClassesJar, '.') 'jar cf classes.jar'
Pop-Location
Info ("classes.jar  " + [math]::Round((Get-Item $ClassesJar).Length / 1KB, 1) + " KB")

Run (Join-Path $Bt 'd8.bat') @(
    '--release',
    '--min-api', '21',
    '--lib', $Plat,
    '--output', $DexDir,
    $ClassesJar
) 'd8'
if (-not (Test-Path $DexFile)) { Die "d8 未产出 classes.dex" }
Info ("classes.dex  " + [math]::Round((Get-Item $DexFile).Length / 1KB, 1) + " KB")

# ------------------------------------------------------------------ 5. 打包
Head "5/7  打包 APK"
Copy-Item $BaseApk $Unsigned -Force
Run (Join-Path $Jdk 'bin\jar.exe') @('uf', $Unsigned, '-C', $DexDir, 'classes.dex') 'jar uf classes.dex'
Info "dex 已写入 APK"

# ------------------------------------------------------------------ 6. 对齐
Head "6/7  zipalign"
Run (Join-Path $Bt 'zipalign.exe') @('-f', '-p', '4', $Unsigned, $Aligned) 'zipalign'

# ------------------------------------------------------------------ 7. 签名
Head "7/7  签名"
if (-not (Test-Path $Keystore)) {
    Info "生成签名密钥（首次构建）"
    New-Item -ItemType Directory -Force -Path (Split-Path $Keystore -Parent) | Out-Null
    Run (Join-Path $Jdk 'bin\keytool.exe') @(
        '-genkeypair',
        '-keystore', $Keystore,
        '-storetype', 'PKCS12',
        '-alias', 'xtlure',
        '-keyalg', 'RSA',
        '-keysize', '2048',
        '-validity', '10950',
        '-storepass', 'xtlure2026',
        '-keypass', 'xtlure2026',
        '-dname', 'CN=Xingtai Lure, OU=Dev, O=XingtaiLure, L=Xingtai, ST=Hebei, C=CN'
    ) 'keytool genkeypair'
} else {
    Info "复用已有密钥 keystore\xtlure.keystore"
}

Run (Join-Path $Bt 'apksigner.bat') @(
    'sign',
    '--ks', $Keystore,
    '--ks-key-alias', 'xtlure',
    '--ks-pass', 'pass:xtlure2026',
    '--key-pass', 'pass:xtlure2026',
    '--v1-signing-enabled', 'true',
    '--v2-signing-enabled', 'true',
    # 关掉 v4：它不写进 APK，而是另生成一个 .idsig 旁挂文件，
    # 只有 adb install --incremental（增量流式安装）才用得到。
    # 正常安装、直接拷贝 APK、各种应用市场都不需要它，
    # 留着只会让人困惑「这个 idsig 是什么、要不要一起发给别人」。
    '--v4-signing-enabled', 'false',
    '--out', $FinalApk,
    $Aligned
) 'apksigner sign'

# v4 关掉后不应再有 .idsig。若因 apksigner 版本差异仍残留就清掉，
# 避免 dist 目录里出现一个没用又容易误解的文件。
$strayIdsig = $FinalApk + '.idsig'
if (Test-Path $strayIdsig) {
    Remove-Item $strayIdsig -Force
    Info "已清理多余的 .idsig 旁挂文件"
}

# ------------------------------------------------------------------ 校验
Head "校验签名"
Run (Join-Path $Bt 'apksigner.bat') @('verify', '--print-certs', $FinalApk) 'apksigner verify'

# 确认签名方案真的在包内：v1 兜 Android 6 及以下，v2 兜 7 及以上，两个都必须有。
# v4 已关闭 —— 它不写进 APK，只用 .idsig 旁挂文件支持增量安装，本工程用不到。
$sigText = (& (Join-Path $Bt 'apksigner.bat') verify --verbose $FinalApk 2>&1) -join "`n"
$sigV1 = $sigText -match 'Verified using v1 scheme[^:]*:\s*true'
$sigV2 = $sigText -match 'Verified using v2 scheme[^:]*:\s*true'
$sigV4 = $sigText -match 'Verified using v4 scheme[^:]*:\s*true'
Info ("签名方案 v1=" + $sigV1 + "  v2=" + $sigV2 + "  v4=" + $sigV4)
if (-not $sigV1) { Die "缺少 v1 签名，Android 6 及以下装不上" }
if (-not $sigV2) { Die "缺少 v2 签名，Android 7 及以上装不上" }
if ($sigV4) { Info "提示：仍检测到 v4 签名（不影响安装）" }

# ------------------------------------------------------------------ 校验版本号
# 必须从 APK 里读回来比对，而不是相信 manifest 或脚本变量 ——
# 之前就是因为版本号写死在两处，改了一处、装出来的包还是旧版本。
Head "校验 APK 内的版本号"
$badging = & (Join-Path $Bt 'aapt2.exe') dump badging $FinalApk 2>&1
$apkVerName = ([regex]::Match(($badging -join "`n"), "versionName='([^']*)'")).Groups[1].Value
$apkVerCode = ([regex]::Match(($badging -join "`n"), "versionCode='([^']*)'")).Groups[1].Value
Info ("APK 实际版本: " + $apkVerName + " (versionCode " + $apkVerCode + ")")
if ($apkVerName -ne $verName) {
    Die ("APK 内 versionName 为「" + $apkVerName + "」，与 manifest 的「" + $verName + "」不一致")
}
if ($apkVerCode -ne $verCode) {
    Die ("APK 内 versionCode 为「" + $apkVerCode + "」，与 manifest 的「" + $verCode + "」不一致")
}
Write-Host ("  版本号一致: " + $apkVerName + " (versionCode " + $apkVerCode + ")") -ForegroundColor Green

Head "构建完成"
$size = (Get-Item $FinalApk).Length
Write-Host ("  APK : " + $FinalApk) -ForegroundColor Green
Write-Host ("  版本: " + $apkVerName + " (versionCode " + $apkVerCode + ")") -ForegroundColor Green
Write-Host ("  大小: " + [math]::Round($size / 1KB, 1) + " KB") -ForegroundColor Green
Write-Host ""
