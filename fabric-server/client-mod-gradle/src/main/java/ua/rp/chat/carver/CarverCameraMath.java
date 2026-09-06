package ua.rp.chat.carver;

/**
 * Pure orbit-camera math for the carving close-up. Angles follow the Minecraft
 * convention (yaw in degrees, {@code atan2(-dx, dz)}; pitch positive when looking
 * down), so results feed the camera pose directly.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverCameraMath {
    /** Fly-to duration in client ticks: sharp but smooth. */
    public static final int FLY_TICKS = 20;
    /** Overshoot of the landing ease: the camera sails ~4% past the anchor and settles back. */
    public static final double FLY_OVERSHOOT = 1.70158 * 1.2;
    public static final float MIN_PITCH = 5.0f;
    public static final float MAX_PITCH = 85.0f;
    public static final double MIN_DIST = 1.2;
    public static final double MAX_DIST = 4.0;
    /** Degrees of orbit per mouse pixel. */
    public static final double DRAG_SENSITIVITY = 0.4;
    /** Meters of zoom per scroll notch. */
    public static final double ZOOM_STEP = 0.25;
    /**
     * Entry framing: close, slightly above, looking at the block corner so two
     * faces read at once instead of one flat wall.
     */
    public static final double ENTRY_DIST = 1.7;
    public static final float ENTRY_PITCH = 42.0f;
    /** Corner offset applied to the entry yaw: face the edge, never the flat. */
    public static final float ENTRY_CORNER_OFFSET = 45.0f;
    /** Work framing: a touch wider and lower, aimed at the socket being carved. */
    public static final double WORK_DIST = 3.4;
    public static final float WORK_PITCH = 28.0f;
    /** Transition from the design orbit to the work framing, in client ticks. */
    public static final int WORK_FLY_TICKS = 20;

    private CarverCameraMath() {
    }

    /**
     * Ease-in-out with a slight overshoot past 1.0 near the end: brisk lift-off,
     * soft landing that almost overflies the anchor. Output exceeds 1.0 mid-landing
     * and returns exactly 1.0 at {@code t = 1}.
     */
    public static double easeInOutBack(double t) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        double c = FLY_OVERSHOOT;
        if (t < 0.5) {
            double u = t * 2.0;
            return Math.pow(u, 2.0) * ((c + 1.0) * u - c) / 2.0;
        }
        double u = t * 2.0 - 2.0;
        return (Math.pow(u, 2.0) * ((c + 1.0) * u + c) + 2.0) / 2.0;
    }

    /** Camera world offset for yaw/pitch/dist around the origin; returns {x, y, z}. */
    public static double[] orbitOffset(double yawDeg, double pitchDeg, double dist) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double horizontal = Math.cos(pitch) * dist;
        return new double[]{
                -Math.sin(yaw) * horizontal,
                Math.sin(pitch) * dist,
                Math.cos(yaw) * horizontal};
    }

    /** Yaw/pitch that looks from (fx, fy, fz) at (tx, ty, tz); returns {yaw, pitch}. */
    public static double[] lookAt(double fx, double fy, double fz,
                                  double tx, double ty, double tz) {
        double dx = tx - fx;
        double dy = ty - fy;
        double dz = tz - fz;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) return new double[]{0.0, 0.0};
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -dy / length))));
        return new double[]{yaw, pitch};
    }

    public static float clampPitch(float pitch) {
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }

    public static double clampDist(double dist) {
        return Math.max(MIN_DIST, Math.min(MAX_DIST, dist));
    }

    /** Shortest-arc angle lerp in degrees. */
    public static float lerpAngle(float from, float to, float t) {
        float delta = ((to - from + 540.0f) % 360.0f) - 180.0f;
        return from + delta * t;
    }

    /**
     * Work-framing transition: pitch and distance glide from the design orbit to
     * the work framing over the eased clock, so the view settles without whipping
     * or overshooting into the ground. Pure: unit-testable.
     *
     * @return {pitch, dist} at progress t in 0..1
     */
    public static double[] workFraming(double pitchFrom, double distFrom, double t) {
        if (t <= 0.0) return new double[]{pitchFrom, distFrom};
        if (t >= 1.0) return new double[]{WORK_PITCH, WORK_DIST};
        double eased = easeInOutBack(Math.max(0.0, Math.min(1.0, t)));
        return new double[]{
                clampPitch((float) (pitchFrom + (WORK_PITCH - pitchFrom) * eased)),
                clampDist(distFrom + (WORK_DIST - distFrom) * eased)};
    }

    /** Yaw facing away from the block toward the viewer on entry. */
    public static float entryYaw(double viewerX, double viewerZ, double centerX, double centerZ) {
        double dx = viewerX - centerX;
        double dz = viewerZ - centerZ;
        if (dx * dx + dz * dz < 1.0e-6) {
            dx = 1.0;
            dz = 1.0;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
