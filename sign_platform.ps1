# Builds the car version and signs it with the AOSP platform key (test-keys),
# the same as the SAIC apps of the MG4 (serial b3998086d056cffa).
# Result: dist\MG4Launcher-car-<version>.apk
param([string]$BuildType = "Release")

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$apksigner = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 | ForEach-Object { "$($_.FullName)\apksigner.bat" }

Push-Location $root
try {
    & .\gradlew.bat --no-daemon -q "assembleCar$BuildType"
    if ($LASTEXITCODE -ne 0) { throw "Échec de la compilation" }
    $lower = $BuildType.ToLower()
    $src = Get-ChildItem "app\build\outputs\apk\car\$lower" -Filter *.apk | Select-Object -First 1
    $version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    New-Item -ItemType Directory -Force dist | Out-Null
    $out = "dist\MG4Dash-$version.apk"
    & $apksigner sign --key keys\platform.pk8 --cert keys\platform.x509.pem `
        --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false `
        --out $out $src.FullName
    if ($LASTEXITCODE -ne 0) { throw "Échec de la signature" }
    & $apksigner verify --print-certs $out | Select-String "Signer #1 certificate (DN|SHA-1)"
    Write-Host "APK signé : $out"
} finally {
    Pop-Location
}
