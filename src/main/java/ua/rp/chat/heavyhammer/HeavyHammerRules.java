package ua.rp.chat.heavyhammer;

public final class HeavyHammerRules {
    public static final int DURATION_TICKS = 34;
    public static final int IMPACT_TICK = 21;
    public static final int COOLDOWN_TICKS = 38;
    public static final double STAMINA_COST = 9.0;
    public static final double FATIGUE_GAIN = 1.35;
    public static final double MAX_TARGET_DISTANCE = 2.20;
    public static final double MAX_HORIZONTAL_TARGET_DISTANCE = 1.65;
    public static final double MIN_TARGET_HEIGHT = -0.30;
    public static final double MAX_TARGET_HEIGHT = 2.35;

    private HeavyHammerRules() {
    }

    public static boolean canImpact(int elapsedTicks) {
        return elapsedTicks >= IMPACT_TICK && elapsedTicks < DURATION_TICKS;
    }
}
