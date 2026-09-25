$ErrorActionPreference = "Stop"

Write-Host "Building release APK..."
.\gradlew.bat assembleRelease

$apk = Get-ChildItem -Path "app\build\outputs\apk\release\*.apk" | Select-Object -First 1
if (-not $apk) {
    Write-Error "No APK found in app\build\outputs\apk\release\"
    exit 1
}

$dest = Join-Path $env:USERPROFILE "Desktop\$($apk.Name)"
Copy-Item $apk.FullName $dest -Force
Write-Host "APK copied to: $dest"
