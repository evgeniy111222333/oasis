package ua.rp.chat.microvoxel;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The "parent block" of a carved volume: the single vanilla material the whole
 * volume reads as for break feedback, mining speed, tool checks and sounds.
 *
 * <p>A marker block is one registry entry for every material, so anything vanilla
 * resolves by itself (particles, break sounds, destroy time) defaults to stone.
 * Every feedback path must therefore route through this resolver instead of the
 * marker state. Dominance (most frequent occupied cell, ties resolve to the first
 * maximum) keeps multi-material sculptures stable: refining details never flips
 * the identity of the block out from under the player.</p>
 *
 * <p>Pure and dependency-free apart from blockstate parsing: safe to unit-test.</p>
 */
public final class MicrovoxelParentage {
    private MicrovoxelParentage() {
    }

    /**
     * Full material string of the dominant remaining cell, or null when the volume
     * is empty or holds no named material. Never falls back to stone: callers decide
     * explicitly what an empty volume means (usually "nothing to break").
     */
    public static String dominantMaterial(MicrovoxelVolume volume) {
        if (volume == null) return null;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        String best = null;
        int bestCount = 0;
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (!volume.occupied(cell)) continue;
            String material = volume.material(cell);
            if (material == null || material.isEmpty()) continue;
            int count = counts.getOrDefault(material, 0) + 1;
            counts.put(material, count);
            if (count > bestCount) {
                bestCount = count;
                best = material;
            }
        }
        return best;
    }

    /** Parsed parent state, or null when the volume is empty or unparsable. */
    public static BlockState parentState(MicrovoxelVolume volume) {
        String dominant = dominantMaterial(volume);
        if (dominant == null) return null;
        try {
            return MicrovoxelBlockStates.parseBlockState(dominant);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Materials by descending cell frequency, capped at {@code limit}: one break
     * event carries a single block id, so mixed sculptures burst once per leading
     * material instead of pretending to be made of one thing.
     */
    public static java.util.List<String> topMaterials(MicrovoxelVolume volume, int limit) {
        if (volume == null || limit <= 0) return java.util.List.of();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (!volume.occupied(cell)) continue;
            String material = volume.material(cell);
            if (material == null || material.isEmpty()) continue;
            counts.put(material, counts.getOrDefault(material, 0) + 1);
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /** Registry id of the parent block, or null when the volume has no parent. */
    public static String parentBlockId(MicrovoxelVolume volume) {
        BlockState parent = parentState(volume);
        if (parent == null) return null;
        try {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(parent.getBlock()).toString();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
