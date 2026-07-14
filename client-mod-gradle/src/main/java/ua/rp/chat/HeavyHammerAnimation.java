package ua.rp.chat;

/** Чистая, тестируемая кинематика двуручного кругового удара. */
public final class HeavyHammerAnimation {
    public static final float DURATION_TICKS = 34.0f;
    public static final float IMPACT_TICK = 21.0f;

    private static final Key[] STRIKE = {
            new Key(0.00f, 0.10f, 0.16f, -0.58f, -0.32f, -0.14f, 0.48f, -1.02f, 0.50f, 0.16f, 0.86f),
            new Key(0.12f, 0.20f, 0.28f, -0.30f, -0.62f, -0.24f, 0.34f, -0.74f, 0.20f, 0.25f, 0.72f),
            new Key(0.32f, -0.02f, -0.48f, -1.30f, -0.92f, 0.42f, 0.22f, -1.18f, -0.72f, -0.30f, 0.55f),
            new Key(0.49f, -0.12f, -0.62f, -2.38f, -0.36f, 0.58f, 0.18f, -2.12f, 0.18f, -0.48f, 0.38f),
            new Key(0.58f, -0.08f, -0.50f, -2.68f, 0.08f, 0.42f, 0.12f, -2.45f, 0.52f, -0.28f, 0.30f),
            new Key(0.618f, 0.08f, 0.35f, -1.35f, 0.22f, -0.18f, 0.28f, -1.44f, 0.14f, 0.14f, 0.72f),
            new Key(0.70f, 0.23f, 0.58f, 0.18f, -0.12f, -0.38f, 0.62f, -0.18f, -0.26f, 0.38f, 1.05f),
            new Key(0.82f, 0.18f, 0.30f, -0.44f, -0.24f, -0.22f, 0.50f, -0.70f, 0.30f, 0.20f, 0.90f),
            new Key(1.00f, 0.10f, 0.16f, -0.58f, -0.32f, -0.14f, 0.48f, -1.02f, 0.50f, 0.16f, 0.86f)
    };

    private HeavyHammerAnimation() {
    }

    public static Sample idle(float ageTicks) {
        float breath = (float) Math.sin(ageTicks * 0.055f) * 0.018f;
        return new Sample(0.0f, 0.10f + breath, 0.16f, -0.58f + breath, -0.32f,
                -0.14f, 0.48f, -1.02f + breath * 0.6f, 0.50f, 0.16f, 0.86f);
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
        return new Sample(progress,
                lerp(from.bodyX, to.bodyX, eased), lerp(from.bodyY, to.bodyY, eased),
                lerp(from.rightX, to.rightX, eased), lerp(from.rightY, to.rightY, eased),
                lerp(from.rightZ, to.rightZ, eased), lerp(from.rightLower, to.rightLower, eased),
                lerp(from.leftX, to.leftX, eased), lerp(from.leftY, to.leftY, eased),
                lerp(from.leftZ, to.leftZ, eased), lerp(from.leftLower, to.leftLower, eased));
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
                       float leftX, float leftY, float leftZ, float leftLower) {
    }

    public record Sample(float progress, float bodyX, float bodyY,
                         float rightX, float rightY, float rightZ, float rightLower,
                         float leftX, float leftY, float leftZ, float leftLower) {
    }
}
