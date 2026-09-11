package ua.rp.chat.carver;

import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

/**
 * Bakes the grain geology into the hologram mesh: a per-domain tint multiplied into the real
 * material colour, so strata, growth rings and crystal facets shade with the actual block
 * lighting instead of floating over it. The colour comes from {@link CarverGrainPalette}, which
 * carries a strong brightness wave per band, a per-domain hue and a seam crease. Pure and
 * dependency-free.
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverGrainTint {
    private CarverGrainTint() {
    }

    /** Tints one material colour by its grain domain, with no seam shading. Pure. */
    public static int apply(int baseArgb, CarverGrainField.GrainType type, int domain,
                            double strength) {
        return apply(baseArgb, type, domain, strength, 0.0);
    }

    /**
     * Tints one material colour by its grain domain and seam closeness. Grainless materials
     * return the colour unchanged, so the renderer can call this unconditionally. Pure.
     */
    public static int apply(int baseArgb, CarverGrainField.GrainType type, int domain,
                            double strength, double boundaryness) {
        if (!CarverGrainField.hasGrain(type)) return baseArgb;
        int tint = CarverGrainPalette.tint(type, domain, strength, boundaryness);
        int alpha = baseArgb >>> 24;
        int r = (((baseArgb >> 16) & 0xFF) * ((tint >> 16) & 0xFF)) / 255;
        int g = (((baseArgb >> 8) & 0xFF) * ((tint >> 8) & 0xFF)) / 255;
        int b = ((baseArgb & 0xFF) * (tint & 0xFF)) / 255;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * The volume cell that owns a greedy-mesh face, resolved from its plane corner and the face
     * direction. Region-gated merging guarantees every cell of the quad shares the domain, so
     * this representative cell is exact. Pure.
     */
    public static int faceCell(MicrovoxelGreedyMesher.Direction direction,
                               int minX, int minY, int minZ) {
        int x = minX;
        int y = minY;
        int z = minZ;
        switch (direction) {
            case UP -> y = minY - 1;
            case SOUTH -> z = minZ - 1;
            case EAST -> x = minX - 1;
            default -> {
            }
        }
        x = clampCell(x);
        y = clampCell(y);
        z = clampCell(z);
        return x | (z << 4) | (y << 8);
    }

    private static int clampCell(int value) {
        return value < 0 ? 0 : value > 15 ? 15 : value;
    }
}
