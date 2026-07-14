package ua.rp.chat;

/** Чистая, тестируемая кинематика двуручного кругового удара. */
public final class HeavyHammerAnimation {
    public static final float DURATION_TICKS = 34.0f;
    public static final float IMPACT_TICK = 21.0f;
    public static final HeavyHammerGripSolver.Point RIGHT_SHOULDER =
            new HeavyHammerGripSolver.Point(-5.0f, 2.0f, 0.0f);
    public static final HeavyHammerGripSolver.Point LEFT_SHOULDER =
            new HeavyHammerGripSolver.Point(5.0f, 2.0f, 0.0f);

    private static final Key[] STRIKE = {
            // progress, корпус, правая рука, локоть, поворот левой ладони, вектор второго хвата
            new Key(0.00f, 0.10f, 0.16f, -0.58f, -0.32f, -0.14f, 0.48f, 0.38f, 2.70f, -3.10f, 0.60f),
            new Key(0.12f, 0.20f, 0.28f, -0.30f, -0.62f, -0.24f, 0.34f, 0.22f, 2.30f, -3.50f, -0.50f),
            new Key(0.32f, -0.02f, -0.48f, -1.30f, -0.92f, -0.12f, 0.22f, -0.42f, 3.10f, -0.30f, 3.00f),
            new Key(0.49f, -0.12f, -0.62f, -2.38f, -0.36f, 0.58f, 0.18f, 0.16f, 3.00f, -0.20f, 3.00f),
            new Key(0.58f, -0.08f, -0.50f, -2.68f, 0.08f, 0.42f, 0.12f, 0.35f, 2.90f, 0.30f, 3.00f),
            new Key(0.618f, 0.08f, 0.35f, -1.35f, 0.22f, -0.18f, 0.28f, 0.10f, 3.10f, -0.10f, 3.00f),
            new Key(0.70f, 0.23f, 0.58f, 0.18f, -0.12f, -0.38f, 0.62f, -0.22f, 2.20f, -2.40f, -2.50f),
            new Key(0.82f, 0.18f, 0.30f, -0.44f, -0.24f, -0.22f, 0.50f, 0.28f, 2.50f, -3.20f, 0.30f),
            new Key(1.00f, 0.10f, 0.16f, -0.58f, -0.32f, -0.14f, 0.48f, 0.38f, 2.70f, -3.10f, 0.60f)
    };

    private HeavyHammerAnimation() {
    }

    public static Sample idle(float ageTicks) {
        float breath = (float) Math.sin(ageTicks * 0.055f) * 0.018f;
        return compose(0.0f, 0.10f + breath, 0.16f,
                -0.58f + breath, -0.32f, -0.14f, 0.48f,
                0.38f, 2.70f, -3.10f, 0.60f);
    }

    public static Sample strike(float elapsedTicks) {
        float progress = clamp(elapsedTicks / DURATION_TICKS, 0.0f, 1.0f);
        Key from = STRIKE[0];
        Key to = STRIKE[STRIKE.length - 1];
        for (int index = 1; index < STRIKE.length; index++) {
            if (progress <= STRIKE[index].time) {
                from = STRIKE[index - 1];
                to = STRIKE[index];
                break;
            }
        }
        float local = (progress - from.time) / Math.max(0.0001f, to.time - from.time);
        float eased = local * local * (3.0f - 2.0f * local);
        return compose(progress,
                lerp(from.bodyX, to.bodyX, eased), lerp(from.bodyY, to.bodyY, eased),
                lerp(from.rightX, to.rightX, eased), lerp(from.rightY, to.rightY, eased),
                lerp(from.rightZ, to.rightZ, eased), lerp(from.rightLower, to.rightLower, eased),
                lerp(from.leftY, to.leftY, eased),
                lerp(from.gripX, to.gripX, eased), lerp(from.gripY, to.gripY, eased),
                lerp(from.gripZ, to.gripZ, eased));
    }

    private static Sample compose(float progress, float bodyX, float bodyY,
                                  float rightX, float rightY, float rightZ, float rightLower,
                                  float leftY, float gripX, float gripY, float gripZ) {
        HeavyHammerGripSolver.Point mainHand = HeavyHammerGripSolver.hand(
                RIGHT_SHOULDER, rightX, rightZ, rightLower);
        HeavyHammerGripSolver.Point offhandTarget = mainHand.add(gripX, gripY, gripZ);
        HeavyHammerGripSolver.Solution left = HeavyHammerGripSolver.solve(LEFT_SHOULDER, offhandTarget);
        return new Sample(progress, bodyX, bodyY,
                rightX, rightY, rightZ, rightLower,
                left.upperX(), leftY, left.upperZ(), left.lowerX(),
                gripX, gripY, gripZ, left.clampDistance());
    }

    public static boolean impactReached(float previousTicks, float currentTicks) {
        return previousTicks < IMPACT_TICK && currentTicks >= IMPACT_TICK;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Key(float time, float bodyX, float bodyY,
                       float rightX, float rightY, float rightZ, float rightLower,
                       float leftY, float gripX, float gripY, float gripZ) {
    }

    public record Sample(float progress, float bodyX, float bodyY,
                         float rightX, float rightY, float rightZ, float rightLower,
                         float leftX, float leftY, float leftZ, float leftLower,
                         float gripX, float gripY, float gripZ, float gripClampDistance) {
    }
}
