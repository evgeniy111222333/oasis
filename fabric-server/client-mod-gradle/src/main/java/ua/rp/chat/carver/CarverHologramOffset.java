package ua.rp.chat.carver;

/**
 * Pure lateral-offset math for the carver hologram.
 *
 * <p>The hologram always lifts straight up. A sideways nudge applies only when a
 * neighbouring solid block would hide the lifted cube from one side: the cube steps
 * away from the occluder so the orbiting player keeps full view. The scan itself
 * lives in the client hologram (a handful of cached block-state reads, no raycasts);
 * this helper keeps the push direction, clamping and fall landing exact and
 * unit-testable.</p>
 *
 * <p>Axes follow Minecraft: east is +X, west is -X, south is +Z, north is -Z.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverHologramOffset {
    /** Hard cap of the sideways nudge in blocks; the cube never leaves its socket column. */
    public static final double MAX_LATERAL = 0.4;

    private CarverHologramOffset() {
    }

    /**
     * Pushes the hologram away from solid side neighbours. Each flag reports whether
     * that side occludes the lifted cube (the caller ORs the socket level and the lift
     * level). Opposing walls cancel out; a lone wall pushes the full cap; a corner
     * pushes diagonally clamped back to the cap radius.
     *
     * @return {offsetX, offsetZ} in blocks, vector length never exceeds {@link #MAX_LATERAL}
     */
    public static double[] compute(boolean eastSolid, boolean westSolid,
                                   boolean northSolid, boolean southSolid) {
        return compute(eastSolid, westSolid, northSolid, southSolid, MAX_LATERAL);
    }

    static double[] compute(boolean eastSolid, boolean westSolid,
                            boolean northSolid, boolean southSolid, double max) {
        double pushX = (westSolid ? 1.0 : 0.0) - (eastSolid ? 1.0 : 0.0);
        double pushZ = (northSolid ? 1.0 : 0.0) - (southSolid ? 1.0 : 0.0);
        if (pushX == 0.0 && pushZ == 0.0) return new double[]{0.0, 0.0};
        double length = Math.sqrt(pushX * pushX + pushZ * pushZ);
        double scale = Math.max(0.0, max) / length;
        return new double[]{pushX * scale, pushZ * scale};
    }

    /**
     * Scales the nudge with the current lift so the falling copy lands back into the
     * socket instead of beside it: full nudge at rest height, zero at touchdown.
     */
    public static double falloff(double offset, double lift, double restLift) {
        if (restLift <= 0.0 || lift <= 0.0) return 0.0;
        if (lift >= restLift) return offset;
        return offset * (lift / restLift);
    }
}
