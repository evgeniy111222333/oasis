package ua.rp.chat.carver;

/**
 * Hammer-butt trajectory locked to the strike contact.
 *
 * <p>The butt travels on the face-normal shaft so the visual impact always lands
 * on the contact point regardless of which side the artisan approached from.
 * Only a small {@link CarverStrikeAlign#NORMAL_BLEND} of the player direction is
 * mixed in for organic feel; the normal dominates by design.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class CarverTrajectory {
    /** Tool length from contact to hammer butt at full extension. */
    public static final double TOOL_LEN = 0.81;
    /** Rest length fraction at lift=0 (hammer parked on the chisel). */
    public static final double REST_FRACTION = 0.25;
    /** Extra butt height at full windup. */
    public static final double LIFT_HEIGHT = 0.30;
    /** Butt offset along the normal at the exact impact frame. */
    public static final double IMPACT_OFFSET = 0.02;

    private CarverTrajectory() {
    }

    /**
     * Butt world position for a windup lift in [0, 1]. At lift=0 the hammer sits
     * on the chisel; at lift=1 it is fully raised. The shaft always passes through
     * the contact, so the strike cannot miss geometrically.
     */
    public static double[] buttPoint(double contactX, double contactY, double contactZ,
                                     double normalX, double normalY, double normalZ,
                                     double toPlayerX, double toPlayerY, double toPlayerZ,
                                     double lift) {
        double l = Math.max(0.0, Math.min(1.0, lift));
        double sx = normalX * (1.0 - CarverStrikeAlign.NORMAL_BLEND)
                + toPlayerX * CarverStrikeAlign.NORMAL_BLEND;
        double sy = normalY * (1.0 - CarverStrikeAlign.NORMAL_BLEND)
                + toPlayerY * CarverStrikeAlign.NORMAL_BLEND;
        double sz = normalZ * (1.0 - CarverStrikeAlign.NORMAL_BLEND)
                + toPlayerZ * CarverStrikeAlign.NORMAL_BLEND;
        double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (!(len > 1.0e-9)) {
            sx = normalX;
            sy = normalY;
            sz = normalZ;
            len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        }
        if (len > 1.0e-9) {
            sx /= len;
            sy /= len;
            sz /= len;
        }
        double reach = TOOL_LEN * (REST_FRACTION + (1.0 - REST_FRACTION) * l);
        return new double[]{contactX + sx * reach,
                contactY + sy * reach + l * LIFT_HEIGHT,
                contactZ + sz * reach};
    }

    /**
     * Exact impact butt position: contact pushed out along the normal so the head
     * visibly touches stone instead of sinking into it.
     */
    public static double[] impactPoint(double contactX, double contactY, double contactZ,
                                       double normalX, double normalY, double normalZ) {
        return new double[]{contactX + normalX * IMPACT_OFFSET,
                contactY + normalY * IMPACT_OFFSET,
                contactZ + normalZ * IMPACT_OFFSET};
    }

    /** Euclidean miss distance between two world points. Pure measurement helper. */
    public static double missDistance(double[] a, double[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return Double.NaN;
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
