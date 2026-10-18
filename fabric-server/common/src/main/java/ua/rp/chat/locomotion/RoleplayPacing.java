package ua.rp.chat.locomotion;

/** Ground locomotion pacing shared by client prediction and server authority. */
public final class RoleplayPacing {
    public static final double VANILLA_WALK_METERS_PER_SECOND = 4.317;
    public static final double VANILLA_SPRINT_FACTOR = 1.3;
    public static final double VANILLA_SPRINT_METERS_PER_SECOND =
            VANILLA_WALK_METERS_PER_SECOND * VANILLA_SPRINT_FACTOR;
    public static final double VANILLA_CROUCH_INPUT_FACTOR = 0.3;
    public static final double WALK_STRIDE_METERS = 2.25;
    public static final double WALK_CYCLE_SECONDS = 0.90;
    public static final double CROUCH_STRIDE_METERS = 1.15;
    public static final double CROUCH_CYCLE_SECONDS = 1.15;
    public static final double SPRINT_STRIDE_METERS = 3.06;
    public static final double SPRINT_CYCLE_SECONDS = 0.85;
    public static final double WALK_FACTOR = WALK_STRIDE_METERS / WALK_CYCLE_SECONDS
            / VANILLA_WALK_METERS_PER_SECOND;
    public static final double CROUCH_FACTOR = CROUCH_STRIDE_METERS / CROUCH_CYCLE_SECONDS
            / (VANILLA_WALK_METERS_PER_SECOND * VANILLA_CROUCH_INPUT_FACTOR);
    public static final double SPRINT_FACTOR = SPRINT_STRIDE_METERS / SPRINT_CYCLE_SECONDS
            / VANILLA_SPRINT_METERS_PER_SECOND;

    private RoleplayPacing() { }

    public static double factor(boolean sprinting, boolean crouching) {
        return sprinting ? SPRINT_FACTOR : crouching ? CROUCH_FACTOR : WALK_FACTOR;
    }
}
