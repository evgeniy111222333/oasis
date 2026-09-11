package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import ua.rp.chat.carver.CarverGrainField;
import ua.rp.chat.carver.CarverInclusionField;
import ua.rp.chat.carver.CarverMaterialView;
import ua.rp.chat.carver.CarverWorkAnim;

/**
 * Phase 0 inspection state: the coherent grain field, the linked inclusion structures and the
 * material readout of the focused piece, cached for the life of the session. Also owns the
 * sonar scan (an active pulse that reveals the true shape of the inclusions for a moment, on a
 * cooldown). The world overlay and the design panel both read it; nothing here touches the
 * network or scans the world per frame.
 */
public final class CarverInspection {
    /** Length of the entry scan pulse, in client ticks. */
    private static final int SCAN_TICKS = 30;
    /** How long a sonar reveal lasts, in client ticks (2.5 s). */
    private static final int SONAR_TICKS = 50;
    /** Sonar cooldown, in client ticks (6 s). */
    private static final int SONAR_COOLDOWN = 120;

    private static boolean enabled = true;
    private static boolean grainArrows = true;
    private static boolean lensActive;
    private static BlockPos focus;
    private static long seed;
    private static CarverWorkAnim.Material material = CarverWorkAnim.Material.GENERIC;
    private static CarverGrainField.GrainType grain = CarverGrainField.GrainType.AMORPHOUS;
    private static CarverGrainField.Field field;
    private static CarverMaterialView.Readout readout;
    private static java.util.List<CarverInclusionField.Structure> structures = java.util.List.of();
    private static int scanTicks = -1;
    private static int sonarTicks = -1;
    private static int sonarCooldown;

    /** Called once per session when the hologram opens. */
    public static void begin(BlockPos focusPos, String blockId, float hardness) {
        focus = focusPos == null ? null : focusPos.immutable();
        seed = focus == null ? 0L : focusSeed(focus);
        readout = CarverMaterialView.of(blockId, hardness);
        material = readout.material();
        grain = readout.grain();
        field = CarverGrainField.hasGrain(grain) ? CarverGrainField.build(seed, grain) : null;
        structures = CarverInclusionField.structures(seed, material);
        scanTicks = 0;
        sonarTicks = -1;
        sonarCooldown = 0;
    }

    public static void clear() {
        focus = null;
        readout = null;
        field = null;
        structures = java.util.List.of();
        scanTicks = -1;
        sonarTicks = -1;
        sonarCooldown = 0;
    }

    public static void clientTick() {
        if (scanTicks >= 0 && ++scanTicks > SCAN_TICKS) {
            scanTicks = -1;
        }
        if (sonarTicks >= 0 && ++sonarTicks > SONAR_TICKS) {
            sonarTicks = -1;
        }
        if (sonarCooldown > 0) {
            sonarCooldown--;
        }
    }

    /** Fires the sonar scan when off cooldown; returns false when it is still recharging. */
    public static boolean triggerSonar() {
        if (focus == null || sonarCooldown > 0 || sonarTicks >= 0) {
            return false;
        }
        sonarTicks = 0;
        sonarCooldown = SONAR_COOLDOWN;
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level != null && focus != null) {
                minecraft.level.playLocalSound(
                        focus.getX() + 0.5, focus.getY() + 1.2, focus.getZ() + 0.5,
                        net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                        SoundSource.PLAYERS, 0.9f, 0.7f, false);
            }
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Whether directional grain arrows are drawn on the working face. */
    public static boolean grainArrows() {
        return grainArrows;
    }

    public static void toggleGrainArrows() {
        grainArrows = !grainArrows;
    }

    /** Whether the X-ray grain lens is showing the raw domain map. */
    public static boolean lensActive() {
        return lensActive;
    }

    public static void toggleLens() {
        lensActive = !lensActive;
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

    /** Built coherent grain field, or null for grainless materials. */
    public static CarverGrainField.Field field() {
        return field;
    }

    public static CarverMaterialView.Readout readout() {
        return readout;
    }

    public static java.util.List<CarverInclusionField.Structure> structures() {
        return structures;
    }

    /** True while a sonar reveal is playing. */
    public static boolean sonarActive() {
        return sonarTicks >= 0;
    }

    /** Sonar reveal progress 0..1, or 1 when idle. */
    public static double sonarProgress() {
        if (sonarTicks < 0) return 1.0;
        return Math.min(1.0, sonarTicks / (double) SONAR_TICKS);
    }

    /** Sonar cooldown progress 0..1 (0 ready, 1 just fired). */
    public static double sonarCooldown01() {
        return Math.max(0.0, Math.min(1.0, sonarCooldown / (double) SONAR_COOLDOWN));
    }

    /** Entry scan pulse progress 0..1 (1 once finished). */
    public static double scanProgress() {
        if (scanTicks < 0) return 1.0;
        return Math.min(1.0, scanTicks / (double) SCAN_TICKS);
    }

    /**
     * Deterministic geology seed of a socket. Uses the raw block coordinates so the same world
     * position always yields the same grain and inclusions across every client.
     */
    private static long focusSeed(BlockPos pos) {
        return CarverGrainField.seedFor(pos.getX(), pos.getY(), pos.getZ());
    }

    private CarverInspection() {
    }
}
