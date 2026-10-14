# Launch the current Fabric server and its matching Eclipse server mod.
$ErrorActionPreference = "Stop"
$serverScript = Join-Path $PSScriptRoot "fabric-server\run.ps1"
if (!(Test-Path -LiteralPath $serverScript -PathType Leaf)) {
    throw "Fabric server launch script not found: $serverScript"
}

Write-Host "Starting Eclipse Roleplay Fabric server..." -ForegroundColor Cyan
& $serverScript
