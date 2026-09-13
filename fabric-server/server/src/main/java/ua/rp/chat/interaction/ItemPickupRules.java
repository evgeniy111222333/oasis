package ua.rp.chat.interaction;

import java.util.UUID;

/** Server-side eligibility checks for deliberate, right-click item pickup. */
public final class ItemPickupRules {
    /** Match the normal close-range entity interaction distance. */
    public static final double MAX_INTERACTION_DISTANCE = 3.0;
    private static final double MAX_INTERACTION_DISTANCE_SQUARED =
            MAX_INTERACTION_DISTANCE * MAX_INTERACTION_DISTANCE;

    private ItemPickupRules() {
    }

    public static boolean mayPickUp(boolean canPlayerPickup, int pickupDelay,
                                    UUID pickupTarget, UUID player, double distanceSquared) {
        return canPlayerPickup
                && pickupDelay <= 0
                && (pickupTarget == null || pickupTarget.equals(player))
                && distanceSquared <= MAX_INTERACTION_DISTANCE_SQUARED;
    }
}
