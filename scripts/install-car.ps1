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

function Invoke-Adb([string[]]$a) { $ErrorActionPreference = "Continue"; $out = & adb.exe @a 2>&1; if ($LASTEXITCODE -ne 0) { throw "adb $($a -join ' '): $out" }; $out }

$before = (Invoke-Adb @("shell", "date", "+%s")).Trim()
Write-Host "Installing $Apk"
Invoke-Adb @("push", $Apk, "/data/local/tmp/x.apk") | Out-Null
try {
    $r = Invoke-Adb @("shell", "pm", "install", "-r", "-f", "/data/local/tmp/x.apk")
    if (-not ($r -match "Success")) { throw "install failed: $r" }
} finally {
    & adb shell rm -f /data/local/tmp/x.apk | Out-Null
}

$rescue = 0
$info = & adb shell dumpsys package app.mg4dash.rescue 2>$null | Select-String "versionCode=(\d+)" | Select-Object -First 1
if ($info) { $rescue = [int]$info.Matches[0].Groups[1].Value }

if ($rescue -ge 4) {
    Write-Host "MG4Dash Rescue ${rescue}: hand-over (clean recompilation, relaunch, watch)"
    # A crash before this (the old process started again on the APK being replaced) is not the build's
    $since = (Invoke-Adb @("shell", "date", "+%s")).Trim()
    Invoke-Adb @("shell", "am", "broadcast", "-n", "app.mg4dash.rescue/.BootReceiver", "-a", "app.mg4dash.rescue.UPDATED", "-f", "0x10000000") | Out-Null
    # Its stop, recompilation and relaunch, then MG4Dash's start
    Start-Sleep -Seconds 45
} else {
    Write-Host "MG4Dash Rescue $rescue (older): README sequence"
    Invoke-Adb @("shell", "am", "force-stop", $pkg) | Out-Null
    Invoke-Adb @("shell", "cmd", "package", "compile", "-m", "speed", "-f", $pkg) | Out-Null
    Invoke-Adb @("shell", "am", "force-stop", $pkg) | Out-Null
    $since = (Invoke-Adb @("shell", "date", "+%s")).Trim()
    # Out of the "stopped" state (its strip, its maintenance gesture, the boot), without opening anything
    & adb.exe shell am broadcast -n app.mg4dash/.system.WakeReceiver -a app.mg4dash.WAKE -f 0x10000020 | Out-Null
    Write-Host "Recompiled: press Home on the car to start MG4Dash, then press Enter here"
    [void](Read-Host)
}

# Checks
$v = Invoke-Adb @("shell", "dumpsys", "package", $pkg) | Select-String "versionName=|versionCode=" | Select-Object -First 2
Write-Host ($v -join "`n")
$dex = Invoke-Adb @("shell", "dumpsys", "package", "dexopt") | Out-String
$m = [regex]::Match($dex, "\[$([regex]::Escape($pkg))\][\s\S]*?status=([^\]\s]+)")
if ($m.Success) { Write-Host "Compiled code: $($m.Groups[1].Value)"; if ($m.Groups[1].Value -match "speed-profile") { Write-Warning "still speed-profile" } }
$procId = "$(& adb shell pidof $pkg)".Trim()
if ($procId) { Write-Host "MG4Dash running (pid $procId)" } else { Write-Warning "MG4Dash not running" }
# MG4Dash's crashes only (another app's are not this install's), split at the hand-over / recompilation
function Get-DashCrashes([string]$from) {
    $lines = @(& adb logcat -d -b crash -T "$from.000" 2>$null)
    $out = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "FATAL EXCEPTION" -and $i + 1 -lt $lines.Count -and $lines[$i + 1] -match "Process: $([regex]::Escape($pkg)),") {
            $out += $lines[$i]; $out += $lines[$i + 1]; if ($i + 2 -lt $lines.Count) { $out += $lines[$i + 2] }
        }
    }
    $out
}
$all = @(Get-DashCrashes $before)
$after = @(Get-DashCrashes $since)
if ($all.Count -gt $after.Count) { Write-Host "During the APK replacement, before the recompilation (not counted): $(($all.Count - $after.Count) / 3) crash(es) of the old process" }
if ($after.Count -gt 0) { Write-Warning "New crash since the recompilation:"; $after | Select-Object -First 6 | ForEach-Object { Write-Host $_ } } else { Write-Host "No new crash" }
