package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import ua.rp.chat.carver.CarverMaterialView;
import ua.rp.chat.carver.CarverWorkAnim;

/**
 * Phase 0 inspection state: the material readout of the focused piece, cached for the life of the
 * session. The world overlay and the design panel both read it; nothing here touches the network
 * or scans the world per frame.
 */
public final class CarverInspection {
    private static boolean enabled = true;
    private static BlockPos focus;
    private static CarverWorkAnim.Material material = CarverWorkAnim.Material.GENERIC;
    private static CarverMaterialView.Readout readout;

    /** Called once per session when the hologram opens. */
    public static void begin(BlockPos focusPos, String blockId, float hardness) {
        focus = focusPos == null ? null : focusPos.immutable();
        readout = CarverMaterialView.of(blockId, hardness);
        material = readout.material();
    }

    public static void clear() {
        focus = null;
        readout = null;
    }

    public static void clientTick() {
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

    public static CarverWorkAnim.Material material() {
        return material;
    }

    public static CarverMaterialView.Readout readout() {
        return readout;
    }

    private CarverInspection() {
    }
}
