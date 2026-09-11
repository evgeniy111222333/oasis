package ua.rp.chat.carver;

/**
 * Material-derived shading for the baked grain. The renderer multiplies the real block texture
 * by the colour returned here, so the geology always reads as its own material instead of a
 * flat wash:
 * <ul>
 *   <li>a per-domain brightness wave makes bedding / rings / crystal facets step,</li>
 *   <li>a small earthy hue is mixed in per domain so adjacent grains separate in colour,</li>
 *   <li>cells sitting on a domain seam are darkened (a baked crease) so the structure has a
 *       visible edge even on busy textures.</li>
 * </ul>
 * Pure and dependency-free: the whole palette is snapshot-tested away from Minecraft.
 *
 * <p>Mirror contract: duplicated verbatim in the paired server module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class CarverGrainPalette {
    /** Earthy seam colours mixed into the material, indexed by {@code domain % n}. */
    private static final int[] HUE = {
            0x8FA0B8, 0xB89A78, 0x88A88F, 0xB88888, 0x9A88B8, 0x88A0B8,
    };
    /** Brightness wave amplitude across domains (much stronger than the old gentle tint). */
    private static final double BAND_AMPLITUDE = 0.34;
    /** Strongest hue mix at full grain strength. */
    private static final double HUE_MIX = 0.30;
    /** Seam crease darkening at a full domain boundary. */
    private static final double EDGE_AO = 0.40;

    private CarverGrainPalette() {
    }

    /**
     * Multiplier colour for one grain cell, applied over the real block texture. Grainless
     * materials return identity white so callers can tint unconditionally. Pure.
     */
    public static int tint(CarverGrainField.GrainType type, int domain, double strength,
                           double boundaryness) {
        if (!CarverGrainField.hasGrain(type)) return 0xFFFFFFFF;
        double s = clamp01(strength);
        double wave = Math.sin(domain * 1.7 + 0.6);
        double brightness = 1.0 + BAND_AMPLITUDE * wave * (0.45 + 0.55 * s);
        brightness *= 1.0 - EDGE_AO * clamp01(boundaryness);
        int base = clamp255((int) Math.round(brightness * 255.0));
        int hue = HUE[Math.floorMod(domain, HUE.length)];
        double mix = HUE_MIX * (0.4 + 0.6 * s);
        int r = clamp255((int) Math.round(base + (((hue >> 16) & 0xFF) - base) * mix));
        int g = clamp255((int) Math.round(base + (((hue >> 8) & 0xFF) - base) * mix));
        int b = clamp255((int) Math.round(base + ((hue & 0xFF) - base) * mix));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Preview colour for the grain lens: a flat, saturated domain colour (identity for none). */
    public static int lensColor(CarverGrainField.GrainType type, int domain, double strength) {
        if (!CarverGrainField.hasGrain(type)) return 0x66A0A0A0;
        double s = 0.5 + 0.5 * clamp01(strength);
        int hue = HUE[Math.floorMod(domain, HUE.length)];
        int r = clamp255((int) Math.round(((hue >> 16) & 0xFF) * s + 40));
        int g = clamp255((int) Math.round(((hue >> 8) & 0xFF) * s + 40));
        int b = clamp255((int) Math.round((hue & 0xFF) * s + 40));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }

    private static double clamp01(double value) {
        if (!(value > 0.0)) return 0.0;
        return value >= 1.0 ? 1.0 : value;
    }
}
