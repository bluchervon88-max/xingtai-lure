# 跑 Swift 侧的跨平台一致性契约测试。
#
# 前提：已装 Swift for Windows（swift-*-RELEASE-windows10.exe）与 Visual Studio Build Tools。
# 环境准备见 tools/swift-env.ps1。
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File ios\swift-test.ps1
#
# 与 run-tests.ps1（Java 侧）的分工：
#   两边读同一份 testdata/vectors.json，输出格式一致，可以直接 diff。
#   任何一边偏离契约，那边就会红 —— 这是防止"改了 Java 忘了改 Swift"的唯一可靠手段。

$ErrorActionPreference = 'Stop'

try { chcp 65001 | Out-Null } catch { }
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$Proj   = Split-Path -Parent $PSScriptRoot
$Rel    = $PSScriptRoot
$OutDir = Join-Path $Proj 'build\swift'

Write-Host ''
Write-Host '########## Swift 侧契约测试（XtLureCore）##########' -ForegroundColor Cyan

# ---------------------------------------------------------------- 环境
. (Join-Path $Proj 'tools\swift-env.ps1')

if (-not (Get-Command swiftc -ErrorAction SilentlyContinue)) {
    Write-Host '  找不到 swiftc。请先安装 Swift for Windows：' -ForegroundColor Red
    Write-Host '     https://www.swift.org/install/windows/' -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---------------------------------------------------------------- 编译
$sources = @(
    (Join-Path $Rel 'Sources\XtLureCore\CoordinateTransform.swift'),
    (Join-Path $Rel 'Sources\XtLureCore\SunMoon.swift'),
    (Join-Path $Rel 'Sources\VectorTest\Vec.swift'),
    (Join-Path $Rel 'Sources\VectorTest\main.swift')
)

foreach ($s in $sources) {
    if (-not (Test-Path $s)) {
        Write-Host "  缺少源文件: $s" -ForegroundColor Red
        exit 1
    }
}

$exe = Join-Path $OutDir 'vectortest.exe'
if (Test-Path $exe) { Remove-Item $exe -Force }

Write-Host '  编译中…' -ForegroundColor DarkGray
# swiftc 一次收齐所有文件。
# Swift 规定：多文件编译时只有 main.swift 允许写顶层语句，
# 所以入口必须是这个文件名（第一次写成 VectorTest.swift 就报了
# 'statements are not allowed at the top level'）。
& swiftc -O @sources -o $exe 2>&1 | ForEach-Object { "    $_" }

if (-not (Test-Path $exe)) {
    Write-Host '  编译失败' -ForegroundColor Red
    exit 1
}
Write-Host '  编译通过' -ForegroundColor Green
Write-Host ''

# ---------------------------------------------------------------- 运行
# 切到工程根目录：契约文件按当前工作目录向上查找
Set-Location $Proj
& $exe
$code = $LASTEXITCODE

Write-Host ''
if ($code -ne 0) {
    Write-Host '  Swift 侧契约测试未通过' -ForegroundColor Red
    exit $code
}
Write-Host '  Swift 侧契约测试通过' -ForegroundColor Green
