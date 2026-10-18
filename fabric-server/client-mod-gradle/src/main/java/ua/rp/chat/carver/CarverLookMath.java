package ua.rp.chat.carver;

/**
 * Player look angles toward a world point, Minecraft convention.
 *
 * <p>Yaw {@code atan2(-dx, dz)} faces +Z at 0 and +X at -90; pitch
 * {@code atan2(-dy, horiz)} reads -90 up and +90 down. Both match
 * {@code LocalPlayer} turn semantics, so forcing these angles aims the eyes
 * exactly at the target with no follow-up correction.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverLookMath {
    private CarverLookMath() {
    }

    /** Yaw/pitch in degrees aiming the eyes at the target. Pure. */
    public static double[] lookYawPitch(double eyeX, double eyeY, double eyeZ,
                                        double targetX, double targetY, double targetZ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double yaw;
        if (horiz > 1.0e-6) {
            yaw = Math.toDegrees(Math.atan2(-dx, dz));
        } else {
            yaw = dy >= 0.0 ? 0.0 : 180.0;
        }
        double pitch = Math.toDegrees(Math.atan2(-dy, Math.max(1.0e-6, horiz)));
        return new double[]{yaw, pitch};
    }
}
