package ua.rp.chat.microvoxel;

import net.minecraft.world.item.ItemDisplayContext;

/**
 * Computes a readable, bounded presentation for portable microvoxel workpieces.
 *
 * <p>The serialized geometry remains exact. Only hand and inventory presentation is normalized:
 * its occupied bounds are centred on the vanilla item pivot and small fragments are enlarged
 * progressively. A single cell remains visibly smaller than a complete workpiece, while a full
 * 16x16x16 volume occupies 60% of a world block in either hand.</p>
 */
public final class MicrovoxelItemScale {
    public static final float TARGET_HAND_BLOCK_FRACTION = 0.60f;
    public static final String PRESENTATION_IDENTITY = "eclipse:microvoxel-presentation-v2";
    static final float VANILLA_FIRST_PERSON_BLOCK_SCALE = 0.40f;
    static final float VANILLA_THIRD_PERSON_BLOCK_SCALE = 0.375f;
    static final float VANILLA_GUI_BLOCK_SCALE = 0.625f;
    private static final float MIN_CELL_FRACTION = 1.0f / 16.0f;

    private MicrovoxelItemScale() {
    }

    /** Legacy full-volume multiplier retained for callers and regression checks. */
    public static float multiplier(ItemDisplayContext context) {
        return presentation(context, fullBounds(), inheritedScale(context)).scale();
    }

    public static Presentation presentation(ItemDisplayContext context,
                                            MicrovoxelVisualShape.Bounds bounds,
                                            float actualInheritedScale) {
        float width = Math.max(0.0f, bounds.maxX() - bounds.minX());
        float height = Math.max(0.0f, bounds.maxY() - bounds.minY());
        float depth = Math.max(0.0f, bounds.maxZ() - bounds.minZ());
        float major = Math.max(width, Math.max(height, depth));
        if (major <= 0.0f) {
            return new Presentation(1.0f, 0.5f, 0.5f, 0.5f, false);
        }

        boolean hand = isHand(context);
        boolean gui = context == ItemDisplayContext.GUI;
        if (!hand && !gui) {
            float scale = switch (context) {
                case GROUND, FIXED, ON_SHELF -> 0.55f;
                default -> 0.65f;
            };
            return new Presentation(scale, 0.5f, 0.5f, 0.5f, false);
        }

        float occupiedProgress = clamp01((major - MIN_CELL_FRACTION)
                / (1.0f - MIN_CELL_FRACTION));
        // Square-root growth gives chips enough visual presence without making them as large as
        // intact workpieces. GUI receives a little more space because a 16 px slot loses detail.
        float minimumTarget = gui ? 0.42f : 0.34f;
        float maximumTarget = gui ? 0.68f : TARGET_HAND_BLOCK_FRACTION;
        float target = minimumTarget
                + (maximumTarget - minimumTarget) * (float) Math.sqrt(occupiedProgress);
        float inherited = Math.max(0.0001f, Math.abs(actualInheritedScale));
        float scale = target / (inherited * major);
        return new Presentation(
                scale,
                (bounds.minX() + bounds.maxX()) * 0.5f,
                (bounds.minY() + bounds.maxY()) * 0.5f,
                (bounds.minZ() + bounds.maxZ()) * 0.5f,
                true);
    }

    public static Presentation presentation(ItemDisplayContext context,
                                            MicrovoxelVisualShape.Bounds bounds) {
        return presentation(context, bounds, inheritedScale(context));
    }

    public static float inheritedScale(ItemDisplayContext context) {
        return switch (context) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND ->
                    VANILLA_FIRST_PERSON_BLOCK_SCALE;
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND ->
                    VANILLA_THIRD_PERSON_BLOCK_SCALE;
            case GUI -> VANILLA_GUI_BLOCK_SCALE;
            default -> 1.0f;
        };
    }

    public static float resultingMajorFraction(ItemDisplayContext context,
                                               MicrovoxelVisualShape.Bounds bounds) {
        float major = Math.max(
                bounds.maxX() - bounds.minX(),
                Math.max(bounds.maxY() - bounds.minY(), bounds.maxZ() - bounds.minZ()));
        return presentation(context, bounds).scale() * inheritedScale(context) * major;
    }

    public static float resultingHandFraction(ItemDisplayContext context) {
        if (!isHand(context)) return Float.NaN;
        return resultingMajorFraction(context, fullBounds());
    }

    private static boolean isHand(ItemDisplayContext context) {
        return switch (context) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND,
                    THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> true;
            default -> false;
        };
    }

    private static MicrovoxelVisualShape.Bounds fullBounds() {
        return new MicrovoxelVisualShape.Bounds(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record Presentation(float scale, float centerX, float centerY, float centerZ,
                               boolean recenter) {
    }
}
