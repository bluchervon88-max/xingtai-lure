# 一键安装到安卓手机
#
# 前置：手机用数据线连电脑，打开「开发者选项 -> USB 调试」，
#       手机上弹出「允许 USB 调试吗」时点允许。
#
# 用法: powershell -ExecutionPolicy Bypass -File install.ps1

$ErrorActionPreference = 'Continue'

try { chcp 65001 | Out-Null } catch { }
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Adb = 'C:\dev\android\sdk\platform-tools\adb.exe'
$Proj = 'C:\Users\Administrator\Documents\deepseek-harness\default-workspace\xingtai-lure'
$Apk = Join-Path $Proj 'dist\XingtaiLure-debug.apk'
$Pkg = 'com.xtlure.app'

if (-not (Test-Path $Adb)) {
    Write-Host "找不到 adb：$Adb" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $Apk)) {
    Write-Host "还没构建过 APK，先跑一次 build.ps1" -ForegroundColor Red
    exit 1
}

# 显示要装的版本，避免"装的到底是哪个包"这种困惑
$aapt2 = 'C:\dev\android\sdk\build-tools\34.0.0\aapt2.exe'
if (Test-Path $aapt2) {
    $badging = (& $aapt2 dump badging $Apk 2>&1) -join "`n"
    $vn = ([regex]::Match($badging, "versionName='([^']*)'")).Groups[1].Value
    $vc = ([regex]::Match($badging, "versionCode='([^']*)'")).Groups[1].Value
    Write-Host ("待安装: " + (Split-Path $Apk -Leaf) + "   版本 " + $vn + " (versionCode " + $vc + ")") -ForegroundColor Cyan
}

Write-Host "=== 检查连接的设备 ===" -ForegroundColor Cyan
& $Adb start-server 2>&1 | Out-Null
$devices = & $Adb devices
$devices | ForEach-Object { Write-Host "  $_" }

$online = ($devices | Select-String -Pattern '\sdevice$').Count
if ($online -lt 1) {
    Write-Host ""
    Write-Host "没有检测到已授权的设备。请检查：" -ForegroundColor Yellow
    Write-Host "  1. 手机是否插好数据线（有些线只能充电）"
    Write-Host "  2. 是否已打开「开发者选项 -> USB 调试」"
    Write-Host "  3. 手机上是否点了「允许 USB 调试」"
    Write-Host "  4. 手机 USB 模式是否选成「传输文件 / MTP」"
    exit 1
}

Write-Host ""
Write-Host "=== 安装 APK ===" -ForegroundColor Cyan
& $Adb install -r "$Apk"
if ($LASTEXITCODE -ne 0) {
    Write-Host "安装失败。若提示签名冲突，先在手机上卸载旧版再试。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== 启动 App ===" -ForegroundColor Cyan
& $Adb shell am start -n "$Pkg/com.xtlure.app.ui.MainActivity"

Write-Host ""
Write-Host "完成。预置钓点会在首次启动时导入/刷新。" -ForegroundColor Green
Write-Host ""
Write-Host "看运行日志（排错用）:" -ForegroundColor DarkGray
Write-Host "  & '$Adb' logcat -s Seed:V AndroidRuntime:E" -ForegroundColor DarkGray
