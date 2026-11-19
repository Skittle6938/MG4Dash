# Installs a MG4Dash APK on the car over ADB, then makes sure it really starts (19/11: an install left compiled code that
# did not load, "NoClassDefFoundError R$style", and MG4Dash crashed at each start).
#   .\scripts\install-car.ps1 dist\MG4Dash-<version>.apk
# With MG4Dash Rescue 4 or later on the car, the same way as an update from the app: the rescue stops MG4Dash,
# recompiles it cleanly, relaunches and watches it. Otherwise the README's sequence: stop, recompile, stop.
# Then checks: version installed, compiled code not "speed-profile", MG4Dash running, no new crash.
param([Parameter(Mandatory = $true)][string]$Apk)

$ErrorActionPreference = "Stop"
$pkg = "app.mg4dash"
if (-not (Test-Path $Apk)) { throw "No such APK: $Apk" }

function Adb([string[]]$a) { $out = & adb @a 2>&1; if ($LASTEXITCODE -ne 0) { throw "adb $($a -join ' '): $out" }; $out }

$before = (Adb @("shell", "date", "+%s")).Trim()
Write-Host "Installing $Apk"
Adb @("push", $Apk, "/data/local/tmp/x.apk") | Out-Null
try {
    $r = Adb @("shell", "pm", "install", "-r", "-f", "/data/local/tmp/x.apk")
    if (-not ($r -match "Success")) { throw "install failed: $r" }
} finally {
    & adb shell rm -f /data/local/tmp/x.apk | Out-Null
}

$rescue = 0
$info = & adb shell dumpsys package app.mg4dash.rescue 2>$null | Select-String "versionCode=(\d+)" | Select-Object -First 1
if ($info) { $rescue = [int]$info.Matches[0].Groups[1].Value }

if ($rescue -ge 4) {
    Write-Host "MG4Dash Rescue ${rescue}: hand-over (clean recompilation, relaunch, watch)"
    Adb @("shell", "am", "broadcast", "-n", "app.mg4dash.rescue/.BootReceiver", "-a", "app.mg4dash.rescue.UPDATED", "-f", "0x10000000") | Out-Null
    # Its stop, recompilation and relaunch, then MG4Dash's start
    Start-Sleep -Seconds 45
} else {
    Write-Host "MG4Dash Rescue $rescue (older): README sequence"
    Adb @("shell", "am", "force-stop", $pkg) | Out-Null
    Adb @("shell", "cmd", "package", "compile", "-m", "speed", "-f", $pkg) | Out-Null
    Adb @("shell", "am", "force-stop", $pkg) | Out-Null
    Write-Host "Recompiled: press Home on the car to start MG4Dash, then press Enter here"
    [void](Read-Host)
}

# Checks
$v = Adb @("shell", "dumpsys", "package", $pkg) | Select-String "versionName=|versionCode=" | Select-Object -First 2
Write-Host ($v -join "`n")
$dex = Adb @("shell", "dumpsys", "package", "dexopt") | Out-String
$m = [regex]::Match($dex, "\[$([regex]::Escape($pkg))\][\s\S]*?status=([^\]\s]+)")
if ($m.Success) { Write-Host "Compiled code: $($m.Groups[1].Value)"; if ($m.Groups[1].Value -match "speed-profile") { Write-Warning "still speed-profile" } }
$procId = "$(& adb shell pidof $pkg)".Trim()
if ($procId) { Write-Host "MG4Dash running (pid $procId)" } else { Write-Warning "MG4Dash not running" }
$crash = & adb logcat -d -b crash -T "$before.000" 2>$null | Select-String "Process: $pkg,|FATAL EXCEPTION"
if ($crash) { Write-Warning "New crash since the install:"; $crash | Select-Object -First 5 | ForEach-Object { Write-Host $_ } } else { Write-Host "No new crash" }
