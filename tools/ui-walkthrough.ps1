# 逐页巡检真机界面：每个页面截图 + dump 界面树 + 记录关键尺寸。
#
# 为什么需要：用"感觉割裂""误触频繁"这类描述无法定位问题，
# 必须拿到每个控件的真实像素尺寸（尤其可点区域是否 >= 48dp 的触摸规范）。
#
# 用法: powershell -ExecutionPolicy Bypass -File tools/ui-walkthrough.ps1
$ErrorActionPreference = 'Continue'
try { chcp 65001 | Out-Null } catch { }
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Adb = 'C:\dev\android\sdk\platform-tools\adb.exe'
$Pkg = 'com.xtlure.app'
$Out = 'C:\Users\Administrator\Documents\deepseek-harness\default-workspace\xingtai-lure\build\walkthrough'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Shot($name) {
    & $Adb shell "screencap -p /sdcard/w_$name.png" 2>&1 | Out-Null
    & $Adb pull "/sdcard/w_$name.png" (Join-Path $Out "$name.png") 2>&1 | Out-Null
    & $Adb shell "uiautomator dump /sdcard/w_$name.xml" 2>&1 | Out-Null
    & $Adb pull "/sdcard/w_$name.xml" (Join-Path $Out "$name.xml") 2>&1 | Out-Null
}

function TapXy($x, $y, $label) {
    Write-Host ("    点击 " + $label + " @(" + $x + "," + $y + ")")
    & $Adb shell "input tap $x $y" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 1500
}

function Foreground() {
    $l = & $Adb shell dumpsys activity activities 2>&1 | Select-String 'topResumedActivity' | Select-Object -First 1
    if ($l) { return ($l.Line -replace '.*\s(\S+/\S+)\s.*', '$1').Trim() }
    return '?'
}

Write-Host "=== 冷启动 ===" -ForegroundColor Cyan
& $Adb shell "am force-stop $Pkg" 2>&1 | Out-Null
Start-Sleep -Milliseconds 1000
& $Adb shell "am start -n $Pkg/com.xtlure.app.ui.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 5
Write-Host ("    前台: " + (Foreground))

Write-Host "`n=== 1. 首页（钓点）===" -ForegroundColor Cyan
Shot '01-home'

# 找出底部标签栏三个标签的实际中心坐标（按文字定位，最可靠）
$xml = Get-Content (Join-Path $Out '01-home.xml') -Raw -Encoding UTF8
function FindText($text) {
    $re = 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $re)
    if (-not $m.Success) {
        # 文字可能在 bounds 之前
        $re2 = 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="' + [regex]::Escape($text) + '"'
        $m = [regex]::Match($xml, $re2)
    }
    if ($m.Success) {
        $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
        $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
        return @{ x = [int](($x1 + $x2) / 2); y = [int](($y1 + $y2) / 2); h = $y2 - $y1; w = $x2 - $x1 }
    }
    return $null
}

foreach ($tab in @('钓点', '记录', '我的')) {
    $t = FindText $tab
    if ($t) {
        Write-Host ("    标签「" + $tab + "」 中心=(" + $t.x + "," + $t.y + ")  尺寸=" + $t.w + "x" + $t.h)
    } else {
        Write-Host ("    标签「" + $tab + "」未在界面树中找到") -ForegroundColor Yellow
    }
}

# 点"记录"页
$t = FindText '记录'
if ($t) { TapXy $t.x $t.y '记录标签'; Shot '02-records' }

# 点"我的"页
$xml = Get-Content (Join-Path $Out '02-records.xml') -Raw -Encoding UTF8
$t = FindText '我的'
if ($t) { TapXy $t.x $t.y '我的标签'; Shot '03-me' }

# 回首页，进第一个钓点详情
$xml = Get-Content (Join-Path $Out '03-me.xml') -Raw -Encoding UTF8
$t = FindText '钓点'
if ($t) { TapXy $t.x $t.y '钓点标签'; Start-Sleep -Milliseconds 1200; Shot '04-home-back' }

# 点击列表里第一张卡片（用卡片文字定位）
$xml = Get-Content (Join-Path $Out '04-home-back.xml' -ErrorAction SilentlyContinue) -Raw -Encoding UTF8
if (-not $xml) { $xml = Get-Content (Join-Path $Out '01-home.xml') -Raw -Encoding UTF8 }

# 找首个"还没有作钓记录"上方最近的卡片标题：直接用列表区域中部点一下
$card = FindText '黑坑'
if ($card) {
    TapXy $card.x ($card.y) '列表卡片'
    Shot '05-detail'
}

Write-Host "`n=== 巡检完成 ===" -ForegroundColor Green
Get-ChildItem $Out | Select-Object Name, @{n='KB'; e={[math]::Round($_.Length / 1KB, 1)}} | Format-Table -AutoSize
