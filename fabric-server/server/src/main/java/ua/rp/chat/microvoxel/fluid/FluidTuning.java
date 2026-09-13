package ua.rp.chat.microvoxel.fluid;

import ua.rp.chat.SimpleConfig;

/**
 * Live operator tunables for the fluid sim, read from {@code microvoxels.fluid.*} config
 * keys. Refreshed once per minute from the manager tick, so {@code /rpreload} retunes a
 * running server with no restart. Every field documents its own blast radius; all defaults
 * match the profiled values the sim shipped with.
 */
public final class FluidTuning {
    /** Volumes simulated per tick; linear CPU scaler. Default 24. */
    public int maxVolumesPerTick = 24;
    /** Vanilla water placements per tick (drains/spills). Default 8. */
    public int maxOutflowPlacements = 8;
    /** Drip particles per tick. Default 8. */
    public int maxParticles = 8;
    /** Units moved per face-pair equalization. Default 512. */
    public int equalizeBudget = 512;
    /** Boundary cells topped per inflow visit. Default 64. */
    public int inflowTopup = 64;
    /** Units drained per floor cell per visit. Default 4. */
    public int drainPerCell = 4;
    /** Seeped cells per visit. Default 64. */
    public int seepBudget = 64;
    /** Minimum ticks between level syncs of one volume. Default 20. */
    public int syncThrottleTicks = 20;
    /** Units moved per lateral relaxation pass. Default 512. */
    public int lateralBudget = 512;

    /** Re-reads every key with validation; out-of-range values clamp to sane bounds. */
    public void reload(SimpleConfig config) {
        maxVolumesPerTick = clamp(config.getInt("microvoxels.fluid.max-volumes-per-tick", 24), 1, 256);
        maxOutflowPlacements = clamp(config.getInt("microvoxels.fluid.max-outflow-placements", 8), 0, 64);
        maxParticles = clamp(config.getInt("microvoxels.fluid.max-particles", 8), 0, 64);
        equalizeBudget = clamp(config.getInt("microvoxels.fluid.equalize-budget", 512), 64, 65536);
        inflowTopup = clamp(config.getInt("microvoxels.fluid.inflow-topup", 64), 0, 1024);
        drainPerCell = clamp(config.getInt("microvoxels.fluid.drain-per-cell", 4), 1, 16);
        seepBudget = clamp(config.getInt("microvoxels.fluid.seep-budget", 64), 0, 1024);
        syncThrottleTicks = clamp(config.getInt("microvoxels.fluid.sync-throttle-ticks", 20), 1, 1200);
        lateralBudget = clamp(config.getInt("microvoxels.fluid.lateral-budget", 512), 64, 65536);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
