package ua.rp.chat;

import ua.rp.chat.client.pickup.PickupPromptLayout;
import ua.rp.chat.client.pickup.PickupPromptRules;

public final class PickupPromptRulesTest {
    public static void main(String[] args) {
        require(PickupPromptRules.isWithinInteractionRange(9.0),
                "Pickup prompt must include the exact interaction boundary");
        require(!PickupPromptRules.isWithinInteractionRange(9.01),
                "Pickup prompt must hide before a server-rejected distant interaction");
        require(PickupPromptRules.advanceFade(0.0f, true) > 0.0f,
                "Prompt must fade in instead of appearing abruptly");
        require(PickupPromptRules.advanceFade(0.05f, false) == 0.0f,
                "Prompt fade-out must clamp cleanly at zero");
        require(PickupPromptRules.advanceFade(0.95f, true) == 1.0f,
                "Prompt fade-in must clamp cleanly at full opacity");

        PickupPromptLayout.Layout normal = PickupPromptLayout.forTitle(1920, 260);
        require(normal.titleRight() + PickupPromptLayout.TITLE_TO_MOUSE_GAP == normal.mouseX(),
                "Title-to-mouse gutter must stay exactly fixed");
        require(normal.titleCapacity() >= 260,
                "Normal HUD widths must reserve the complete title before the mouse column");

        PickupPromptLayout.Layout narrow = PickupPromptLayout.forTitle(180, 600);
        require(narrow.cardWidth() <= 180 - PickupPromptLayout.CARD_SIDE_MARGIN * 2
                        || narrow.cardWidth() == 168,
                "Narrow HUD layout must remain bounded by the safe card width");
        require(narrow.titleRight() < narrow.mouseX(),
                "Mouse icon column must remain to the right of the title boundary");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
