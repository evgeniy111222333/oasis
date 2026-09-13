# Modern Minecraft Server Launch Script with Aikar's GC Flags
# Requires Java 25

$RAM = "4G"

# One-time, non-destructive compatibility bridge for worlds first created by
# Purpur 26.1. Paper stored several global records under the overworld
# dimension, while vanilla/Fabric expects them in world/data/minecraft.
$worldRoot = Join-Path $PSScriptRoot "world"
$globalData = Join-Path $worldRoot "data\minecraft"
$paperOverworldData = Join-Path $worldRoot "dimensions\minecraft\overworld\data\minecraft"
if (Test-Path $paperOverworldData) {
    New-Item -ItemType Directory -Force -Path $globalData | Out-Null
    foreach ($name in @("game_rules.dat", "scheduled_events.dat", "weather.dat", "world_gen_settings.dat")) {
        $source = Join-Path $paperOverworldData $name
        $target = Join-Path $globalData $name
        if ((Test-Path $source) -and !(Test-Path $target)) {
            Copy-Item -LiteralPath $source -Destination $target
        }
    }
}

# Preserve the enabled built-in Paper pack reference as an empty compatibility
# pack. No Paper code or data is loaded; this only lets vanilla read level.dat.
$bukkitPack = Join-Path $worldRoot "datapacks\bukkit\pack.mcmeta"
$paperPack = Join-Path $worldRoot "datapacks\paper\pack.mcmeta"
if ((Test-Path $bukkitPack) -and !(Test-Path $paperPack)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $paperPack) | Out-Null
    Copy-Item -LiteralPath $bukkitPack -Destination $paperPack
}

Write-Host "Starting Minecraft Server with $RAM RAM Allocation..." -ForegroundColor Cyan

java --enable-native-access=ALL-UNNAMED "-Xms$RAM" "-Xmx$RAM" -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=15 -XX:G1HeapRegionSize=32m -XX:G1MixedGCCountTarget=8 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxGCPauseMillis=200 -jar fabric-server-launch.jar nogui

Write-Host "Server stopped." -ForegroundColor Yellow
Pause
