package ua.rp.chat.client.carry;

/** Articulated shoulder/elbow/wrist targets in the existing player rig's radians. */
public final class CarryPoseMath {
    private static final Arm FREE = new Arm(false, 0, 0, 0, 0, 0);
    private CarryPoseMath() {}
    public static Pose sample(boolean large, boolean leftHand, boolean show, double seconds) {
        float breathe = .012f * (float) Math.sin(seconds * 2.1);
        if (large) return new Pose(
                new Arm(true, -.88f + breathe, .12f, -.09f, -.66f, .08f),
                new Arm(true, -.88f + breathe, -.12f, .09f, -.66f, -.08f));
        Arm palm = new Arm(true, (show ? -1.02f : -.48f) + breathe,
                leftHand ? .10f : -.10f, leftHand ? -.12f : .12f,
                show ? -.42f : -.78f, leftHand ? -.16f : .16f);
        return leftHand ? new Pose(palm, FREE) : new Pose(FREE, palm);
    }
    public record Arm(boolean active, float pitch, float yaw, float roll, float elbow, float wrist) {}
    public record Pose(Arm left, Arm right) {}
}
