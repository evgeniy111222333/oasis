package ua.rp.chat.heavyhammer;

public final class HeavyHammerRules {
    public static final int DURATION_TICKS = 34;
    public static final int IMPACT_TICK = 21;
    public static final int COOLDOWN_TICKS = 38;
    public static final double STAMINA_COST = 9.0;
    public static final double FATIGUE_GAIN = 1.35;

    private HeavyHammerRules() {
    }

    public static boolean canImpact(int elapsedTicks) {
        return elapsedTicks >= IMPACT_TICK && elapsedTicks < DURATION_TICKS;
    }
}
