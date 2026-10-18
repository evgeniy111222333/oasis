package ua.rp.chat.carry;

/** One world-space anchor for server projections and interpolated client rendering. */
public final class CarryTransforms {
    public static final double FORWARD = .85;
    public static final double HEIGHT = .55;
    public static final double CROUCH_DROP = .30;
    private CarryTransforms() {}

    public static Point anchor(double x, double y, double z, float yaw, boolean crouching) {
        double radians = Math.toRadians(yaw);
        return new Point(x - Math.sin(radians) * FORWARD,
                y + HEIGHT - (crouching ? CROUCH_DROP : 0), z + Math.cos(radians) * FORWARD);
    }

    public static double modelCenter(boolean blockDisplay, boolean large) {
        return blockDisplay ? 0 : large ? .5 : .18;
    }
    public record Point(double x, double y, double z) {}
}
