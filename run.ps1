# Modern Minecraft Server Launch Script with Aikar's GC Flags
# Requires Java 25

$RAM = "4G"

Write-Host "Starting Minecraft Server with $RAM RAM Allocation..." -ForegroundColor Cyan

java "-Xms$RAM" "-Xmx$RAM" -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=15 -XX:G1HeapRegionSize=32m -XX:G1MixedGCCountTarget=8 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxGCPauseMillis=200 -jar server.jar nogui

Write-Host "Server stopped." -ForegroundColor Yellow
Pause
