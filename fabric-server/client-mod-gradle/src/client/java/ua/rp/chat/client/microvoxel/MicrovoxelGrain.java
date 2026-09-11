package ua.rp.chat.client.microvoxel;

import net.minecraft.core.BlockPos;
import ua.rp.chat.carver.CarverGrainField;
import ua.rp.chat.carver.CarverWorkAnim;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic grain geology of an in-world microvoxel volume. The seed is derived from the
 * raw block position and the family from the volume's dominant material, so every client sees
 * the same strata / rings / facets and a re-entered carving keeps its grain. A bounded LRU keeps
 * the 4K-cell fields from piling up while the compiler walks a town's worth of volumes.
 *
 * <p>The field is shared by the mesher (region-gated merging so each band keeps its own quad)
 * and the section model (per-face tint + seam crease), so the world block finally reads its
 * grain the same way the design hologram does.</p>
 */
public final class MicrovoxelGrain {
    private static final int CACHE_LIMIT = 768;
    private static final Object LOCK = new Object();
    private static final Map<Long, CarverGrainField.Field> CACHE =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CarverGrainField.Field> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private MicrovoxelGrain() {
    }

    /** Grain family of one material string (null / blank falls back to opaque stone). */
    public static CarverGrainField.GrainType typeFor(String material) {
        return CarverGrainField.typeFor(CarverWorkAnim.classify(material));
    }

    /** Cached field for a volume, or null for grainless materials. Never null-safe built twice. */
    public static CarverGrainField.Field fieldFor(BlockPos position, String dominantMaterial) {
        CarverGrainField.GrainType type = typeFor(dominantMaterial);
        if (!CarverGrainField.hasGrain(type)) return null;
        long key = ((long) position.asLong() * 31L) ^ (type.ordinal() + 1L);
        synchronized (LOCK) {
            CarverGrainField.Field cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        CarverGrainField.Field built = CarverGrainField.build(
                CarverGrainField.seedFor(position.getX(), position.getY(), position.getZ()), type);
        synchronized (LOCK) {
            CACHE.put(key, built);
        }
        return built;
    }

    /** Convenience for callers holding a volume: dominant material drives the family. */
    public static CarverGrainField.Field fieldFor(BlockPos position, MicrovoxelVolume volume) {
        return fieldFor(position, MicrovoxelVolume.dominantMaterial(volume));
    }

    /** Drops the whole cache (resource reload / world change). */
    public static void clear() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }
}
