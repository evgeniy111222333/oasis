package ua.rp.chat.microvoxel.mining;

/**
 * Pure microvoxel mining arithmetic mirroring the vanilla destroy-progress formula:
 * progressPerTick = toolSpeed / hardness / divisor, where the divisor is 30 with the correct
 * tool and 100 without it, and hardness -1 (unbreakable) yields zero progress.
 */
public final class MicrovoxelMiningMath {
    public static final float TOOL_DIVISOR_CORRECT = 30.0f;
    public static final float TOOL_DIVISOR_WRONG = 100.0f;
    public static final int MAX_CRACK_STAGE = 9;
    public static final float MIN_MULTIPLIER = 0.01f;

    private MicrovoxelMiningMath() {
    }

    public static float progressPerTick(float toolSpeed, float hardness, boolean correctTool) {
        if (hardness <= 0.0f || toolSpeed <= 0.0f) {
            return 0.0f;
        }
        float divisor = correctTool ? TOOL_DIVISOR_CORRECT : TOOL_DIVISOR_WRONG;
        return Math.max(0.0f, Math.min(1.0f, toolSpeed / (hardness * divisor)));
    }

    public static float requiredTicks(float toolSpeed, float hardness, boolean correctTool,
                                      float multiplier) {
        return requiredTicksFromProgressPerTick(
                progressPerTick(toolSpeed, hardness, correctTool), multiplier);
    }

    public static float requiredTicksFromProgressPerTick(float progressPerTick, float multiplier) {
        if (progressPerTick <= 0.0f) {
            return Float.POSITIVE_INFINITY;
        }
        float scale = multiplier > MIN_MULTIPLIER ? multiplier : 1.0f;
        return (1.0f / progressPerTick) * scale;
    }

    public static int crackStage(float progress, float requiredTicks) {
        if (requiredTicks <= 0.0f || Float.isInfinite(requiredTicks)) {
            return 0;
        }
        float scale = Math.max(0.0f, Math.min(9.999f, progress / requiredTicks * 10.0f));
        return Math.min(MAX_CRACK_STAGE, (int) scale);
    }
}