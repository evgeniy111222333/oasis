package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic brush geometry shared by client preview and server authority.
 *
 * <p>Coordinates are evaluated in one continuous 1/16-block lattice, so a brush crosses block
 * and chunk boundaries without seams or special cases.</p>
 *
 * <p>Mirror contract: this file is intentionally duplicated verbatim in the client module.
 * Any change here must be applied to {@code client-mod-gradle/.../MicrovoxelBrush.java}
 * as well; {@code verifyMicrovoxelNativeMarker} fails the build on divergence.</p>
 */
public final class MicrovoxelBrush {
    public static final int SINGLE = 0;
    public static final int SPHERE = 1;
    public static final int BOX = 2;
    public static final int PLANE = 3;
    public static final int MAX_RADIUS = 4;
    private static final int CELL_MASK = MicrovoxelVolume.CELL_COUNT - 1;

    private MicrovoxelBrush() {
    }

    public static int encode(int cell, int shape, int radius) {
        requireCell(cell);
        requireShape(shape);
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Brush radius out of range: " + radius);
        }
        int radiusCode = radius == 0 ? 0 : radius - 1;
        return cell | (shape << 12) | (radiusCode << 14);
    }

    public static int cell(int encoded) {
        return encoded & CELL_MASK;
    }

    public static int shape(int encoded) {
        return (encoded >>> 12) & 3;
    }

    public static int radius(int encoded) {
        int shape = shape(encoded);
        if (shape == SINGLE) return 0;
        return ((encoded >>> 14) & 3) + 1;
    }

    public static List<Target> targets(
            int blockX, int blockY, int blockZ, int cell, int shape, int radius, Axis normal
    ) {
        requireCell(cell);
        requireShape(shape);
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Brush radius out of range: " + radius);
        }
        if (shape == SINGLE || radius == 0) {
            return List.of(new Target(blockX, blockY, blockZ, cell));
        }
        int originX = blockX * MicrovoxelVolume.RESOLUTION + MicrovoxelVolume.x(cell);
        int originY = blockY * MicrovoxelVolume.RESOLUTION + MicrovoxelVolume.y(cell);
        int originZ = blockZ * MicrovoxelVolume.RESOLUTION + MicrovoxelVolume.z(cell);
        ArrayList<Target> result = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        int radiusSquared = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (shape == SPHERE && dx * dx + dy * dy + dz * dz > radiusSquared) continue;
                    if (shape == PLANE && switch (normal) {
                        case X -> dx != 0;
                        case Y -> dy != 0;
                        case Z -> dz != 0;
                    }) continue;
                    int globalX = originX + dx;
                    int globalY = originY + dy;
                    int globalZ = originZ + dz;
                    int targetBlockX = Math.floorDiv(globalX, MicrovoxelVolume.RESOLUTION);
                    int targetBlockY = Math.floorDiv(globalY, MicrovoxelVolume.RESOLUTION);
                    int targetBlockZ = Math.floorDiv(globalZ, MicrovoxelVolume.RESOLUTION);
                    result.add(new Target(targetBlockX, targetBlockY, targetBlockZ,
                            MicrovoxelVolume.index(
                                    Math.floorMod(globalX, MicrovoxelVolume.RESOLUTION),
                                    Math.floorMod(globalY, MicrovoxelVolume.RESOLUTION),
                                    Math.floorMod(globalZ, MicrovoxelVolume.RESOLUTION))));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= MicrovoxelVolume.CELL_COUNT) {
            throw new IllegalArgumentException("Cell out of range: " + cell);
        }
    }

    private static void requireShape(int shape) {
        if (shape < SINGLE || shape > PLANE) {
            throw new IllegalArgumentException("Brush shape out of range: " + shape);
        }
    }

    public enum Axis { X, Y, Z }

    public record Target(int blockX, int blockY, int blockZ, int cell) {
    }
}
