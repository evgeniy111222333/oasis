package ua.rp.chat.client.pickup;

/**
 * Pixel-stable geometry for the compact pickup focus prompt.
 * The action group owns a reserved right-hand lane, so the item name can never
 * enter it regardless of item name length or GUI scale.
 */
public final class PickupPromptLayout {
    public static final int CARD_SIDE_MARGIN = 16;
    public static final int TEXT_X = 35;
    public static final int RIGHT_PADDING = 10;
    public static final int MOUSE_ICON_WIDTH = 12;
    public static final int MOUSE_TO_LABEL_GAP = 4;
    public static final int TITLE_TO_ACTION_GAP = 10;
    private static final int MIN_CARD_WIDTH = 144;

    private PickupPromptLayout() {
    }

    public static Layout forFocusPrompt(int screenWidth, int desiredTitleWidth, int actionLabelWidth) {
        int maxCardWidth = Math.max(MIN_CARD_WIDTH, screenWidth - CARD_SIDE_MARGIN * 2);
        int actionWidth = MOUSE_ICON_WIDTH + MOUSE_TO_LABEL_GAP + Math.max(0, actionLabelWidth);
        int fixedWidth = TEXT_X + TITLE_TO_ACTION_GAP + actionWidth + RIGHT_PADDING;
        int titleCapacity = Math.max(0, maxCardWidth - fixedWidth);
        int titleWidth = Math.max(0, Math.min(desiredTitleWidth, titleCapacity));
        int cardWidth = Math.min(maxCardWidth, Math.max(MIN_CARD_WIDTH, fixedWidth + titleWidth));
        int actionX = cardWidth - RIGHT_PADDING - actionWidth;
        int titleRight = actionX - TITLE_TO_ACTION_GAP;
        return new Layout(cardWidth, titleRight - TEXT_X, titleRight, actionX);
    }

    public record Layout(int cardWidth, int titleCapacity, int titleRight, int actionX) {
    }
}
