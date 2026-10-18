package ua.rp.chat.carver;

import ua.rp.chat.SimpleConfig;

/**
 * Live operator tunables for the Carver system, read from {@code Carver.*}
 * config keys. Reloaded on the same minute cadence as the fluid sim, so
 * {@code /rpreload} retunes a running server with no restart.
 */
public final class CarverTuning {
    /** Block-reach for scroll targeting. Default 6.25 (one microvoxel reach). */
    public double reach = 6.25;
    /** Design session TTL in seconds before the draft dissolves. Default 300. */
    public int designTimeoutSeconds = 300;
    /** Cells accepted in one stroke packet. Default 512 (one packed mask). */
    public int maxStrokeCells = 512;
    /** Cells accepted in one box-select packet. Default 4096 (the whole volume). */
    public int maxBoxCells = 4096;
    /** Leash in blocks: leaving it cancels design, moving in work cancels work. */
    public double workLeashBlocks = 1.5;
    public double designLeashBlocks = 6.0;
    /** Particle + sound heartbeat during simulated work, in ticks. Default 10. */
    public int workFxIntervalTicks = 10;
    /** Arm-swing animation heartbeat during work, in ticks. Default 20. */
    public int workSwingIntervalTicks = 20;
    /** Master loudness of work sounds. Default 0.8. */
    public double workSoundVolume = 0.8;
    /** Instrumental overlay layer (shears on wool, axe on wood). Default true. */
    public boolean workSoundSnipLayer = true;

    public void reload(SimpleConfig config) {
        reach = clampDouble(config.getDouble("carver.reach", 6.25), 2.0, 12.0);
        designTimeoutSeconds = clamp(config.getInt("carver.design-timeout-seconds", 300), 30, 1800);
        maxStrokeCells = clamp(config.getInt("carver.max-stroke-cells", 512), 16, 4096);
        maxBoxCells = clamp(config.getInt("carver.max-box-cells", 4096), 64, 4096);
        workLeashBlocks = clampDouble(config.getDouble("carver.work-leash-blocks", 1.5), 0.5, 8.0);
        designLeashBlocks = clampDouble(config.getDouble("carver.design-leash-blocks", 6.0), 2.0, 32.0);
        workFxIntervalTicks = clamp(config.getInt("carver.work-fx-interval-ticks", 10), 2, 100);
        workSwingIntervalTicks = clamp(config.getInt("carver.work-swing-interval-ticks", 20), 5, 200);
        workSoundVolume = clampDouble(config.getDouble("carver.work-sound-volume", 0.8), 0.0, 1.0);
        workSoundSnipLayer = config.getBoolean("carver.work-sound-snip-layer", true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
