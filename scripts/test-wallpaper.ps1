<#
.SYNOPSIS
    Builds WeatherWall in Docker, installs it on the containerised Android
    emulator, activates the live wallpaper and captures screenshots + diagnostics.

.DESCRIPTION
    Everything runs inside containers; the Windows host only needs Docker Desktop.

    IMPORTANT: Docker Desktop on Windows cannot expose /dev/kvm, so the emulator
    uses software rendering (SwiftShader). Screenshots and functional behaviour
    are meaningful; absolute FPS / GPU / battery numbers are NOT representative
    of a real device. Treat timing output only as A/B regression signal within
    the identical container configuration.
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [int]$WarmupSeconds = 20,
    [int]$MeasureSeconds = 30
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$Pkg = 'info.olli.weatherlwp'
$Service = "$Pkg/.wallpaper.WeatherWallpaper"
$Emu = 'weatherwall-emulator'

function Invoke-Emu {
    param([Parameter(Mandatory)][string]$Command)
    docker exec $Emu bash -lc $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed in emulator: $Command" }
}

New-Item -ItemType Directory -Force -Path artifacts | Out-Null

if (-not $SkipBuild) {
    Write-Host '==> Building APK...' -ForegroundColor Cyan
    docker compose run --rm build
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
}

$apk = 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }

Write-Host '==> Ensuring emulator is up...' -ForegroundColor Cyan
docker compose up -d emulator | Out-Null

Write-Host '==> Waiting for Android boot (slow without KVM)...' -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(30)
do {
    Start-Sleep -Seconds 15
    $booted = (docker exec $Emu adb shell getprop sys.boot_completed 2>$null)
    if ($booted) { $booted = $booted.Trim() }
    Write-Host "    boot_completed='$booted'"
    if ((Get-Date) -gt $deadline) { throw 'Emulator did not boot in time.' }
} until ($booted -eq '1')

Write-Host '==> Installing APK...' -ForegroundColor Cyan
# Package verification times out on the software-rendered emulator.
Invoke-Emu "adb shell settings put global verifier_verify_adb_installs 0 || true"
Invoke-Emu "adb shell settings put global package_verifier_enable 0 || true"
Invoke-Emu "adb install -r -t /workspace/$apk"

Write-Host '==> Verifying wallpaper service registration...' -ForegroundColor Cyan
Invoke-Emu "adb shell dumpsys package $Pkg | grep -i -A2 WallpaperService || true"

Write-Host '==> Activating live wallpaper...' -ForegroundColor Cyan
# API 34 has no `cmd wallpaper set-live-wallpaper`, so the chooser is used and
# confirmed via key events; fall back to clicking "Set wallpaper" in noVNC.
Invoke-Emu "adb shell svc power stayon true || true"
Invoke-Emu "adb shell input keyevent KEYCODE_WAKEUP || true"
docker exec $Emu adb shell am start `
    -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER `
    --es android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $Service | Out-Null
Start-Sleep -Seconds 10
docker exec $Emu adb shell input keyevent KEYCODE_TAB | Out-Null
docker exec $Emu adb shell input keyevent KEYCODE_ENTER | Out-Null
Start-Sleep -Seconds 10
docker exec $Emu adb shell input keyevent KEYCODE_HOME | Out-Null

Write-Host "==> Warming up for $WarmupSeconds s..." -ForegroundColor Cyan
Invoke-Emu "adb shell dumpsys gfxinfo $Pkg reset > /dev/null 2>&1 || true"
Invoke-Emu "adb logcat -c"
Start-Sleep -Seconds $WarmupSeconds

Write-Host '==> Capturing screenshots...' -ForegroundColor Cyan
Invoke-Emu "mkdir -p /artifacts/screenshots"
for ($i = 1; $i -le 8; $i++) {
    $n = '{0:d2}' -f $i
    Invoke-Emu "adb exec-out screencap -p > /artifacts/screenshots/wallpaper-$n.png"
    Start-Sleep -Seconds 2
}

Write-Host '==> Recording 20 s video...' -ForegroundColor Cyan
Invoke-Emu "adb shell screenrecord --bit-rate 8000000 --time-limit 20 /sdcard/ww.mp4 && adb pull /sdcard/ww.mp4 /artifacts/wallpaper.mp4 && adb shell rm /sdcard/ww.mp4"

Write-Host "==> Collecting diagnostics over $MeasureSeconds s..." -ForegroundColor Cyan
Invoke-Emu "sleep $MeasureSeconds; adb shell dumpsys gfxinfo $Pkg framestats > /artifacts/gfxinfo.txt 2>&1 || true"
Invoke-Emu "adb shell dumpsys meminfo $Pkg > /artifacts/meminfo.txt 2>&1 || true"
Invoke-Emu "adb shell dumpsys cpuinfo > /artifacts/cpuinfo.txt 2>&1 || true"
Invoke-Emu "adb logcat -d > /artifacts/logcat.txt 2>&1 || true"
Invoke-Emu "grep -Ei 'FATAL|ANR|OpenGLRenderer|EGL|Choreographer|Skipped|WeatherWallpaper|ParallaxScene|TerrainLayer' /artifacts/logcat.txt > /artifacts/logcat-relevant.txt || true"

Write-Host ''
Write-Host 'Done. Artifacts in .\artifacts\' -ForegroundColor Green
Write-Host 'Live view: http://localhost:6080' -ForegroundColor Green
Write-Host 'NOTE: FPS/GPU numbers from software emulation are not device-representative.' -ForegroundColor Yellow
