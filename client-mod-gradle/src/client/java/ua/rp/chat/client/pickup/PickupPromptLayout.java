package ua.rp.chat.client.pickup;

/**
 * Pixel-stable geometry for the pickup HUD card.
 * The mouse control owns a reserved right-hand column, so title text can never
 * enter its gutter regardless of item name length or GUI scale.
 */
public final class PickupPromptLayout {
    public static final int CARD_SIDE_MARGIN = 16;
    public static final int TEXT_X = 45;
    public static final int RIGHT_PADDING = 10;
    public static final int MOUSE_ICON_WIDTH = 18;
    public static final int TITLE_TO_MOUSE_GAP = 12;
    private static final int MIN_CARD_WIDTH = 168;

    private PickupPromptLayout() {
    }

    public static Layout forTitle(int screenWidth, int desiredTitleWidth) {
        int maxCardWidth = Math.max(MIN_CARD_WIDTH, screenWidth - CARD_SIDE_MARGIN * 2);
        int fixedWidth = TEXT_X + TITLE_TO_MOUSE_GAP + MOUSE_ICON_WIDTH + RIGHT_PADDING;
        int titleCapacity = Math.max(0, maxCardWidth - fixedWidth);
        int titleWidth = Math.max(0, Math.min(desiredTitleWidth, titleCapacity));
        int cardWidth = Math.min(maxCardWidth, Math.max(MIN_CARD_WIDTH, fixedWidth + titleWidth));
        int mouseX = cardWidth - RIGHT_PADDING - MOUSE_ICON_WIDTH;
        int titleRight = mouseX - TITLE_TO_MOUSE_GAP;
        return new Layout(cardWidth, titleRight - TEXT_X, titleRight, mouseX);
    }

    public record Layout(int cardWidth, int titleCapacity, int titleRight, int mouseX) {
    }
}
