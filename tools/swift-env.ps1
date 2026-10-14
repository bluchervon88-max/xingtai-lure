# Swift on Windows 构建环境准备脚本。
#
# ★ 为什么需要这个脚本
#   Swift 在 Windows 上编译需要 MSVC 的 C++ 工具链环境（INCLUDE / LIB / PATH），
#   这些由 vcvarsall.bat 设置。但直接在批处理里 source 它并追加 PATH 很脆弱：
#   cmd.exe 在中文代码页下会吃掉 set 命令的引号，导致 PATH 根本没设上，
#   报错还是"swiftc 不是内部或外部命令"，看不出真正原因。
#
#   这个脚本改用 PowerShell：先跑 vcvarsall 并把环境 dump 出来解析，
#   再 Set-Item 应用到当前进程。绕开了 cmd 的代码页与引号问题。
#
# 用法（在同一个 PowerShell 会话里 dot-source）：
#   . C:\Users\Administrator\Documents\deepseek-harness\default-workspace\xingtai-lure\tools\swift-env.ps1
#   swiftc main.swift -o main.exe
#   .\main.exe
#
# 注意：必须 dot-source（前面加点），否则环境变量只作用于子作用域。

$ErrorActionPreference = 'Stop'

$swiftRoot = Join-Path $env:LOCALAPPDATA 'Programs\Swift'
if (-not (Test-Path $swiftRoot)) {
    throw "未找到 Swift 安装目录: $swiftRoot（请先安装 swift-*-RELEASE-windows10.exe）"
}

# 找版本目录（如 6.2.4）
$versions = Get-ChildItem (Join-Path $swiftRoot 'Toolchains') -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d' }
if (-not $versions) {
    throw "Toolchains 目录下没有版本目录"
}

# ★ 三个目录的命名规则不一样，不能共用一个版本串：
#   Toolchains/6.2.4+Asserts/     <- 带构建类型后缀
#   Runtimes/6.2.4/              <- 纯版本号
#   Platforms/6.2.4/             <- 纯版本号
# 一开始我三个都用 Toolchains 的目录名，结果 Runtimes 路径拼错，脚本直接抛异常。
$tcName = $versions[-1].Name
$verNum = ($tcName -split '\+')[0]      # 去掉 +Asserts 之类的后缀

$tcBin = Join-Path $swiftRoot "Toolchains\$tcName\usr\bin"
$rtBin = Join-Path $swiftRoot "Runtimes\$verNum\usr\bin"
$sdk = Join-Path $swiftRoot "Platforms\$verNum\Windows.platform\Developer\SDKs\Windows.sdk"

foreach ($p in @($tcBin, $rtBin, $sdk)) {
    if (-not (Test-Path $p)) { throw "缺少路径: $p" }
}

# 找 vcvarsall.bat
$vcvarsCandidates = @(
    'C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvarsall.bat',
    'C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat',
    'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat',
    'C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvarsall.bat'
)
$vcvars = $vcvarsCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $vcvars) {
    # 兜底：全盘搜一次（限定在 VS 目录下，避免全盘扫描）
    $vcvars = Get-ChildItem 'C:\Program Files (x86)\Microsoft Visual Studio',
                           'C:\Program Files\Microsoft Visual Studio' `
                           -Recurse -Filter 'vcvarsall.bat' -ErrorAction SilentlyContinue |
              Select-Object -First 1 -ExpandProperty FullName
}
if (-not $vcvars) {
    throw '找不到 vcvarsall.bat —— 需要安装 Visual Studio Build Tools 的 C++ 工具链'
}

# 跑 vcvarsall 并把环境变量 dump 出来（>nul 抑制它自己的横幅）
Write-Host "  使用 Swift $ver" -ForegroundColor DarkGray
Write-Host "  MSVC: $(Split-Path $vcvars -Leaf) @ $(Split-Path (Split-Path $vcvars) -Parent)" -ForegroundColor DarkGray

$envDump = & cmd.exe /c "`"$vcvars`" x64 >nul 2>&1 && set" 2>&1
if ($LASTEXITCODE -ne 0 -or -not $envDump) {
    throw "vcvarsall 执行失败（退出码 $LASTEXITCODE）"
}

$applied = 0
foreach ($line in $envDump) {
    if ($line -match '^([^=]+)=(.*)$') {
        $name = $Matches[1]
        $value = $Matches[2]
        # PATH 稍后单独处理，避免覆盖掉 Swift 路径
        if ($name -ieq 'PATH') { continue }
        Set-Item -Path "env:$name" -Value $value -ErrorAction SilentlyContinue
        $applied++
    }
}

# Swift 的 bin 放最前面，确保用到的是安装的这版
$env:Path = "$rtBin;$tcBin;" + (($envDump | Where-Object { $_ -match '^PATH=' }) -replace '^PATH=', '')
$env:SDKROOT = $sdk

Write-Host "  ✅ Swift 环境就绪（应用了 $applied 个 MSVC 变量）" -ForegroundColor Green
Write-Host "     SDKROOT = $sdk" -ForegroundColor DarkGray
