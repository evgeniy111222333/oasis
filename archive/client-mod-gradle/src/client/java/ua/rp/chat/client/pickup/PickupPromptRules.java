package ua.rp.chat.client.pickup;

/** Pure client rules shared by the item targeter and its HUD prompt. */
public final class PickupPromptRules {
    public static final double MAX_INTERACTION_DISTANCE = 3.0;
    private static final double MAX_INTERACTION_DISTANCE_SQUARED =
            MAX_INTERACTION_DISTANCE * MAX_INTERACTION_DISTANCE;
    private static final float FADE_STEP = 0.16f;

    private PickupPromptRules() {
    }

    public static boolean isWithinInteractionRange(double distanceSquared) {
        return distanceSquared <= MAX_INTERACTION_DISTANCE_SQUARED;
    }

    public static float advanceFade(float current, boolean visible) {
        float next = current + (visible ? FADE_STEP : -FADE_STEP);
        return Math.max(0.0f, Math.min(1.0f, next));
    }
}
