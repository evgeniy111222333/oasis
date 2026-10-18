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
    /**
     * Lateral deadband in degrees: contact inside it keeps the symmetric CENTER
     * stance, so a millimeter of drift never mirrors the feet. Pure XZ measure.
     */
    public static final double SIDE_DEADBAND_DEG = 15.0;
    /** Widest floor step absorbed by the knees, blocks. Beyond it: hips only. */
    public static final double MAX_FLOOR_STEP = 0.5;
    /** Knee bend per block of floor step, radians. Clamped by MAX_KNEE_BEND. */
    public static final double KNEE_PER_BLOCK = 1.0;
    /** Widest knee bend, radians. */
    public static final double MAX_KNEE_BEND = 0.5;

    /** Which foot leads, from the lateral contact side. Frozen per session. */
    public enum Side { LEFT_LEAD, CENTER, RIGHT_LEAD }

    private CarverWorkStance() {
    }

    /**
     * Signed lateral angle in degrees between the facing yaw and the direction to
     * the contact, measured strictly in the XZ plane (top faces included: the
     * contact underfoot still has an XZ projection). Negative reads contact on the
     * left side of the facing, positive on the right. Pure.
     */
    public static double lateralDeg(double contactX, double contactZ,
                                    double playerX, double playerZ, double entityYawDeg) {
        double dx = contactX - playerX;
        double dz = contactZ - playerZ;
        if (dx * dx + dz * dz < 1.0e-8) return 0.0;
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = (targetYaw - entityYawDeg) % 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return delta;
    }

    /** Lead-foot side with the center deadband. Pure. */
    public static Side sideFor(double lateralDeg) {
        if (lateralDeg < -SIDE_DEADBAND_DEG) return Side.LEFT_LEAD;
        if (lateralDeg > SIDE_DEADBAND_DEG) return Side.RIGHT_LEAD;
        return Side.CENTER;
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
        return stance(Side.LEFT_LEAD);
    }

    /**
     * Foot stance for the frozen lead side. Tools never swap hands (chisel stays
     * left, hammer right); only the feet mirror, so the strike choreography and
     * the tool rendering survive untouched. Pure.
     */
    public static LegStance stance(Side side) {
        if (side == Side.RIGHT_LEAD) {
            return new LegStance(TRAIL_LEG_PITCH, LEAD_LEG_PITCH, -FOOT_SPLAY, FOOT_SPLAY);
        }
        if (side == Side.CENTER) {
            float half = (LEAD_LEG_PITCH + TRAIL_LEG_PITCH) * 0.25f;
            return new LegStance(half, -half, -FOOT_SPLAY, FOOT_SPLAY);
        }
        return new LegStance(LEAD_LEG_PITCH, TRAIL_LEG_PITCH, -FOOT_SPLAY, FOOT_SPLAY);
    }

    /**
     * Scales the stance by the work entry blend (0 outside work, 1 settled in).
     * Pure.
     */
    public static LegStance blended(double entryBlend) {
        double b = Math.max(0.0, Math.min(1.0, entryBlend));
        LegStance full = stance(Side.LEFT_LEAD);
        float bF = (float) b;
        return new LegStance(full.leftPitch() * bF, full.rightPitch() * bF,
                full.leftYaw() * bF, full.rightYaw() * bF);
    }

    /**
     * Full working legs: side mirror plus floor-step knees. {@code floorStep} is
     * left-ground minus right-ground in blocks (positive: left foot uphill),
     * clamped to {@link #MAX_FLOOR_STEP}; the uphill knee absorbs it, the pelvis
     * drops half of it. Returns thigh pitches, knee bends and the hip drop in
     * model units (1/16 block each, same unit as the existing work dip). Pure.
     */
    public record FullLegs(float thighLeft, float thighRight,
                           float kneeLeft, float kneeRight, float hipDrop) {
    }

    public static FullLegs blended(double entryBlend, Side side, double floorStep) {
        double b = Math.max(0.0, Math.min(1.0, entryBlend));
        LegStance base = stance(side);
        double dh = Math.max(-MAX_FLOOR_STEP, Math.min(MAX_FLOOR_STEP, floorStep));
        double kneeUphill = Math.min(MAX_KNEE_BEND, Math.abs(dh) * KNEE_PER_BLOCK);
        double kneeLeft = dh > 0.0 ? kneeUphill : 0.0;
        double kneeRight = dh < 0.0 ? kneeUphill : 0.0;
        double thighLeft = base.leftPitch() + (dh > 0.0 ? kneeUphill * 0.5 : -kneeUphill * 0.3);
        double thighRight = base.rightPitch() + (dh < 0.0 ? kneeUphill * 0.5 : -kneeUphill * 0.3);
        double hipDrop = Math.abs(dh) * 16.0 * 0.5;
        float bF = (float) b;
        return new FullLegs((float) (thighLeft * b), (float) (thighRight * b),
                (float) (kneeLeft * b), (float) (kneeRight * b), (float) (hipDrop * b));
    }
}
