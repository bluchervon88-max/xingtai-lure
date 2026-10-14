$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Jdk      = 'C:\MCLDownload\ext\jre-v64-220420\jdk17'
$Root     = 'C:\dev\android'
$Sdk      = Join-Path $Root 'sdk'
$CmdTools = Join-Path $Sdk 'cmdline-tools\latest'
$Zip      = Join-Path $Root 'cmdline-tools.zip'

$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:PATH = "$Jdk\bin;$env:PATH"

function Step($m) { Write-Output ("[" + (Get-Date -Format 'HH:mm:ss') + "] " + $m) }

Step "root=$Root  jdk=$Jdk"
New-Item -ItemType Directory -Force -Path $Root | Out-Null

# ---------- 1. cmdline-tools ----------
if (-not (Test-Path (Join-Path $CmdTools 'bin\sdkmanager.bat'))) {
  if (-not (Test-Path $Zip)) {
    Step "downloading commandlinetools ..."
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $Zip -UseBasicParsing -TimeoutSec 1800
  } else { Step "cmdline-tools zip already present" }
  Step ("zip size = " + [math]::Round((Get-Item $Zip).Length/1MB,1) + " MB")
  $tmp = Join-Path $Root 'cmdtools-extract'
  if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
  Step "extracting ..."
  Expand-Archive -Path $Zip -DestinationPath $tmp -Force
  New-Item -ItemType Directory -Force -Path (Split-Path $CmdTools -Parent) | Out-Null
  if (Test-Path $CmdTools) { Remove-Item $CmdTools -Recurse -Force }
  Move-Item -Path (Join-Path $tmp 'cmdline-tools') -Destination $CmdTools
  Remove-Item $tmp -Recurse -Force
  Step "cmdline-tools installed"
} else { Step "cmdline-tools already installed" }

# ---------- 2. packages ----------
$SdkManager = Join-Path $CmdTools 'bin\sdkmanager.bat'
Step "accepting licenses ..."
$y = ("y`r`n" * 60)
$y | & $SdkManager --sdk_root=$Sdk --licenses 2>&1 | Select-Object -Last 5

$pkgs = @(
  'platform-tools',
  'build-tools;34.0.0',
  'platforms;android-34'
)
foreach ($p in $pkgs) {
  Step "installing $p ..."
  $y | & $SdkManager --sdk_root=$Sdk $p 2>&1 | Select-Object -Last 6
}

# ---------- 3. verify ----------
Step "=== VERIFY ==="
foreach ($f in @(
  'build-tools\34.0.0\aapt2.exe',
  'build-tools\34.0.0\d8.bat',
  'build-tools\34.0.0\apksigner.bat',
  'build-tools\34.0.0\zipalign.exe',
  'build-tools\34.0.0\lib\d8.jar',
  'platforms\android-34\android.jar',
  'platform-tools\adb.exe'
)) {
  $full = Join-Path $Sdk $f
  Write-Output ("  " + $(if (Test-Path $full) { 'OK      ' } else { 'MISSING ' }) + $f)
}
Step "=== aapt2 version ==="
& (Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe') version 2>&1
Step "=== DONE ==="
