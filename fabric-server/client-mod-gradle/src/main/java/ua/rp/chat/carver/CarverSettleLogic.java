package ua.rp.chat.carver;

/**
 * Pure decision core of the autowalk settle phase.
 *
 * <p>After the walk arrives, the artisan must stop moving and ease the body yaw
 * onto the planned strike yaw before approval. This state machine has exactly one
 * yaw target per tick (SEEK faces the stand while stepping, ALIGN faces the strike
 * yaw while standing still), so the two targets can never fight and visibly spin
 * the character. The close band has hysteresis: it engages under
 * {@link #SETTLE_POS_TOL} and releases past {@link #SETTLE_POS_TOL_RELEASE}, so
 * physics jitter on the boundary cannot flap the movement keys.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverSettleLogic {
    /** Settle approval budget in ticks before a yaw-snapped approve. */
    public static final int SETTLE_TICKS = 10;
    /** Engage distance (blocks) to the stand for the ALIGN phase. */
    public static final double SETTLE_POS_TOL = 0.15;
    /** Release distance (blocks): leaving past it returns to SEEK. */
    public static final double SETTLE_POS_TOL_RELEASE = 0.30;
    /** Yaw error (degrees) accepted for a clean aligned approve. */
    public static final float SETTLE_YAW_TOL = 2.0f;

    /** One-tick settle decision. */
    public enum Action {
        /** Step towards the stand facing it; yaw target is the walk face. */
        SEEK,
        /** Stand still, ease yaw onto the strike yaw. */
        ALIGN,
        /** Aligned: approve immediately, no snap needed. */
        APPROVE_ALIGNED,
        /** Budget spent: snap yaw onto the strike yaw, then approve. */
        APPROVE_TIMEOUT,
    }

    private CarverSettleLogic() {
    }

    /**
     * Next settle action. Pure.
     *
     * @param distXZ     horizontal distance to the stand.
     * @param yawErrAbs  absolute yaw error to the strike yaw, degrees.
     * @param settleTicks ticks already spent settling.
     * @param wasClose   close-latch state from the previous tick (hysteresis).
     */
    public static Action next(double distXZ, float yawErrAbs, int settleTicks, boolean wasClose) {
        boolean close = distXZ < (wasClose ? SETTLE_POS_TOL_RELEASE : SETTLE_POS_TOL);
        if (!close) return Action.SEEK;
        if (yawErrAbs < SETTLE_YAW_TOL) return Action.APPROVE_ALIGNED;
        if (settleTicks >= SETTLE_TICKS) return Action.APPROVE_TIMEOUT;
        return Action.ALIGN;
    }

    /** Close-latch update for the next tick (hysteresis memory). Pure. */
    public static boolean latch(double distXZ, boolean wasClose) {
        return distXZ < (wasClose ? SETTLE_POS_TOL_RELEASE : SETTLE_POS_TOL);
    }

    /** Smallest absolute yaw delta in degrees, wrapped to [0, 180]. Pure. */
    public static float yawErr(float fromDeg, float toDeg) {
        float d = (toDeg - fromDeg) % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return Math.abs(d);
    }
}
