package ua.rp.chat;

/**
 * Процедурная походка при переноске тяжёлого молота.
 *
 * <p>Ширина опоры задаётся положением стоп, а тазобедренные шарниры всегда
 * остаются в исходных точках. При наборе скорости силовая стойка сужается до
 * обычного шага, колено маховой ноги подбирается, а корпус компенсирует массу
 * молота движением, противоположным шагу.</p>
 */
public final class HeavyHammerGait {
    private static final float IDLE_STANCE_OFFSET = 0.62f;
    private static final float WALK_STANCE_OFFSET = 0.12f;
    private static final float RUN_STANCE_OFFSET = 0.06f;

    private HeavyHammerGait() {
    }

    public static Sample sample(float walkPosition, float walkAnimationSpeed,
                                float linearSpeed, boolean crouching) {
        float movementInput = Math.max(walkAnimationSpeed * 3.2f, linearSpeed * 7.0f);
        float movement = smootherStep(clamp(movementInput, 0.0f, 1.0f));
        float run = smootherStep(clamp((linearSpeed - 0.12f) / 0.12f, 0.0f, 1.0f));
        float phase = walkPosition * 0.6662f;
        float step = (float) Math.sin(phase);

        float stride = movement * lerp(0.48f, 0.64f, run);
        float rightHipPitch = -step * stride;
        float leftHipPitch = step * stride;

        float rightSwing = Math.max(0.0f, -step);
        float leftSwing = Math.max(0.0f, step);
        float baseKnee = lerp(0.085f, 0.11f, movement);
        float kneeLift = lerp(0.31f, 0.42f, run) * movement;
        float rightKnee = baseKnee + rightSwing * kneeLift;
        float leftKnee = baseKnee + leftSwing * kneeLift;
        if (crouching) {
            rightHipPitch -= 0.16f;
            leftHipPitch -= 0.16f;
            rightKnee += 0.25f;
            leftKnee += 0.25f;
        }

        float stanceOffset = lerp(IDLE_STANCE_OFFSET, WALK_STANCE_OFFSET, movement);
        stanceOffset = lerp(stanceOffset, RUN_STANCE_OFFSET, run * movement);
        float idleOutwardRoll = lerp(0.006f, 0.0f, movement);
        float lateralTransfer = (float) Math.cos(phase) * movement * 0.012f;
        float rightHipRoll = idleOutwardRoll + lateralTransfer;
        float leftHipRoll = -idleOutwardRoll + lateralTransfer;

        float torsoYaw = -step * movement * lerp(0.030f, 0.045f, run);
        float torsoRoll = -lateralTransfer * 0.70f;
        float torsoPitch = movement * lerp(0.012f, 0.045f, run);
        return new Sample(movement, run, rightHipPitch, leftHipPitch,
                rightHipRoll, leftHipRoll, rightKnee, leftKnee, stanceOffset,
                torsoPitch, torsoYaw, torsoRoll);
    }

    public static float footSeparation(Sample sample) {
        return ArticulatedLimbLayout.footCenterSeparation(
                sample.rightHipRoll, sample.leftHipRoll, sample.stanceOffset);
    }

    private static float smootherStep(float value) {
        return value * value * value * (value * (value * 6.0f - 15.0f) + 10.0f);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Sample(float movement, float run,
                         float rightHipPitch, float leftHipPitch,
                         float rightHipRoll, float leftHipRoll,
                         float rightKnee, float leftKnee, float stanceOffset,
                         float torsoPitch, float torsoYaw, float torsoRoll) {
    }
}
