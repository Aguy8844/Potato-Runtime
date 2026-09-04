$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "Potato Runtime verification" -ForegroundColor Cyan
.\gradlew.bat clean build --stacktrace

if ($LASTEXITCODE -ne 0) {
    throw "Potato Runtime build failed."
}

Write-Host ""
Write-Host "BUILD PASS" -ForegroundColor Green
Write-Host "Next: .\gradlew.bat runClient"
