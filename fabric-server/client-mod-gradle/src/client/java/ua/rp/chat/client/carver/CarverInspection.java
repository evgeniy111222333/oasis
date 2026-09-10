package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import ua.rp.chat.carver.CarverGrainField;
import ua.rp.chat.carver.CarverInclusionField;
import ua.rp.chat.carver.CarverMaterialView;
import ua.rp.chat.carver.CarverWorkAnim;

/**
 * Phase 0 inspection state: the procedural grain and inclusion hints of the focused piece,
 * plus the material readout, cached for the life of the session. The world overlay and the
 * design panel both read it; nothing here touches the network or scans the world per frame.
 */
public final class CarverInspection {
    /** How many inclusion hints one workpiece may show (keeps the overlay readable). */
    private static final int MAX_HINTS = 24;
    /** Length of the entry scan pulse, in client ticks. */
    private static final int SCAN_TICKS = 30;

    private static boolean enabled = true;
    private static BlockPos focus;
    private static long seed;
    private static CarverWorkAnim.Material material = CarverWorkAnim.Material.GENERIC;
    private static CarverGrainField.GrainType grain = CarverGrainField.GrainType.AMORPHOUS;
    private static CarverMaterialView.Readout readout;
    private static java.util.List<CarverInclusionField.Hint> hints = java.util.List.of();
    private static int scanTicks = -1;

    /** Called once per session when the hologram opens. */
    public static void begin(BlockPos focusPos, String blockId, float hardness) {
        focus = focusPos == null ? null : focusPos.immutable();
        seed = focus == null ? 0L : focusSeed(focus);
        readout = CarverMaterialView.of(blockId, hardness);
        material = readout.material();
        grain = readout.grain();
        hints = CarverInclusionField.hints(seed, material, MAX_HINTS);
        scanTicks = 0;
    }

    public static void clear() {
        focus = null;
        readout = null;
        hints = java.util.List.of();
        scanTicks = -1;
    }

    public static void clientTick() {
        if (scanTicks >= 0 && ++scanTicks > SCAN_TICKS) {
            scanTicks = -1;
        }
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static BlockPos focus() {
        return focus;
    }

    public static long seed() {
        return seed;
    }

    public static CarverWorkAnim.Material material() {
        return material;
    }

    public static CarverGrainField.GrainType grain() {
        return grain;
    }

    public static CarverMaterialView.Readout readout() {
        return readout;
    }

    public static java.util.List<CarverInclusionField.Hint> hints() {
        return hints;
    }

    /** Entry scan pulse progress 0..1 (1 once finished). */
    public static double scanProgress() {
        if (scanTicks < 0) return 1.0;
        return Math.min(1.0, scanTicks / (double) SCAN_TICKS);
    }

    /**
     * Deterministic geology seed of a socket. Uses the raw block coordinates so the same
     * world position always yields the same grain and inclusions across every client.
     */
    private static long focusSeed(BlockPos pos) {
        long h = pos.getX() * 0x9E3779B97F4A7C15L
                + pos.getY() * 0xC2B2AE3D27D4EB4FL
                + pos.getZ() * 0x165667B19E3779F9L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return h;
    }

    private CarverInspection() {
    }
}
