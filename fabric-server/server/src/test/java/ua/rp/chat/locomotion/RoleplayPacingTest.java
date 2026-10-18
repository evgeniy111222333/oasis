package ua.rp.chat.locomotion;

/** Cadence checks use the authored clip lengths and measured native ground speeds. */
public final class RoleplayPacingTest {
    public static void main(String[] args) {
        ordinaryWalkUsesAuthoredCadence();
        crouchUsesAuthoredCadence();
        sprintKeepsItsNativeSpeed();
        vitalsAndSlownessKeepTheirRelativePenalties();
        System.out.println("RoleplayPacingTest passed");
    }

    private static void ordinaryWalkUsesAuthoredCadence() {
        double speed = 4.317 * RoleplayPacing.factor(false, false);
        near(speed, 2.5, "Ordinary walking speed");
        near(RoleplayPacing.WALK_STRIDE_METERS / speed, RoleplayPacing.WALK_CYCLE_SECONDS,
                "Ordinary walk must preserve the calibrated 0.90 second cycle");
    }

    private static void crouchUsesAuthoredCadence() {
        double speed = 4.317 * 0.3 * RoleplayPacing.factor(false, true);
        near(speed, 1.0, "Crouch speed");
        near(RoleplayPacing.CROUCH_STRIDE_METERS / speed, RoleplayPacing.CROUCH_CYCLE_SECONDS,
                "Crouch must preserve the calibrated 1.15 second cycle");
    }

    private static void sprintKeepsItsNativeSpeed() {
        double speed = RoleplayPacing.VANILLA_SPRINT_METERS_PER_SECOND * RoleplayPacing.factor(true, false);
        near(speed, 3.6, "RP sprint speed");
        near(RoleplayPacing.SPRINT_STRIDE_METERS / speed, RoleplayPacing.SPRINT_CYCLE_SECONDS,
                "Sprint must preserve the calibrated 0.85 second cycle");
        near(RoleplayPacing.VANILLA_SPRINT_METERS_PER_SECOND * RoleplayPacing.factor(true, true), 3.6,
                "Sprint state takes priority over a stale crouch state");
    }

    private static void vitalsAndSlownessKeepTheirRelativePenalties() {
        double healthy = 4.317 * RoleplayPacing.factor(false, false);
        double injured = 4.317 * (0.075 / 0.2) * RoleplayPacing.factor(false, false);
        double slowed = healthy * 0.7;
        near(injured, 0.9375, "Existing vitals movement penalty must compose with RP pace");
        near(slowed, 1.75, "Existing slowness must compose with RP pace");
        near(injured / healthy, 0.375, "Vitals must retain their relative movement penalty");
    }

    private static void near(double actual, double expected, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1.0e-10) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
