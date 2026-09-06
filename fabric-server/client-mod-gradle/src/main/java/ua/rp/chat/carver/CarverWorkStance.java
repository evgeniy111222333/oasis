package ua.rp.chat.carver;

/**
 * Torso-and-legs stance for the carving work pose.
 *
 * <p>Why this exists: arms and head are aimed in world space at the strike
 * contact, but the torso and legs are left to vanilla (entity yaw + idle legs).
 * Whenever the frozen entity yaw differs from the strike direction, the artisan
 * reads as "arms and head work the block while the body stands elsewhere". This
 * helper turns the torso toward the contact and plants the feet in a staggered
 * working stance, so the pose is self-sufficient and never depends on the exact
 * entity yaw. Arm and head parts are siblings of the body part (never children),
 * so rotating the torso cannot drag the world-aimed hands or gaze off target.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverWorkStance {
    /** Widest torso turn toward the contact, radians. Beyond it the neck takes over. */
    public static final double MAX_BODY_TURN = 0.6;
    /** Left (lead) foot forward pitch, radians at full blend. */
    public static final float LEAD_LEG_PITCH = 0.28f;
    /** Right (trail) foot back pitch, radians at full blend. */
    public static final float TRAIL_LEG_PITCH = -0.22f;
    /** Outward foot splay, radians at full blend. */
    public static final float FOOT_SPLAY = 0.06f;

    private CarverWorkStance() {
    }

    /**
     * Torso turn toward the contact, relative to the entity yaw. Positive turns
     * the chest left, negative right; clamped so the spine never corkscrews past
     * what the neck and arms can still cover. Pure.
     */
    public static double bodyTurn(double worldYawToContactDeg, double entityYawDeg) {
        double delta = (worldYawToContactDeg - entityYawDeg) % 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        double radians = Math.toRadians(delta);
        return Math.max(-MAX_BODY_TURN, Math.min(MAX_BODY_TURN, radians));
    }

    /** Staggered foot stance at full entry blend. Pure. */
    public record LegStance(float leftPitch, float rightPitch, float leftYaw, float rightYaw) {
    }

    public static LegStance stance() {
        return new LegStance(LEAD_LEG_PITCH, TRAIL_LEG_PITCH, -FOOT_SPLAY, FOOT_SPLAY);
    }

    /**
     * Scales the stance by the work entry blend (0 outside work, 1 settled in).
     * Pure.
     */
    public static LegStance blended(double entryBlend) {
        double b = Math.max(0.0, Math.min(1.0, entryBlend));
        LegStance full = stance();
        float bF = (float) b;
        return new LegStance(full.leftPitch() * bF, full.rightPitch() * bF,
                full.leftYaw() * bF, full.rightYaw() * bF);
    }
}
