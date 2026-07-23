# Fabric 26.1.2 build and deployment script.
# Uses the root Gradle build so server/client verification and distribution sync
# are identical to CI and never compile against Purpur/Paper classes.

$ErrorActionPreference = "Stop"
$workspace = $PSScriptRoot
$gradle = Join-Path $workspace "gradlew.bat"

if (!(Test-Path $gradle)) {
    throw "Gradle wrapper not found: $gradle"
}

Write-Host "Building and verifying Eclipse Fabric server/client..." -ForegroundColor Cyan
& $gradle -p $workspace :server:clean :server:check :server:build :client:check :client:build
if ($LASTEXITCODE -ne 0) {
    throw "Fabric build failed with exit code $LASTEXITCODE"
}

$serverMod = Join-Path $PSScriptRoot "mods\eclipseserver-1.4.5.jar"
if (!(Test-Path $serverMod)) {
    throw "Fabric server mod was not deployed: $serverMod"
}

Write-Host "Build succeeded. Fabric server and client distributions are deployed." -ForegroundColor Green
