package ua.rp.chat.client.microvoxel;

import net.minecraft.core.BlockPos;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared client-side volume/fluid registry, extracted from {@link MicrovoxelClientState}.
 *
 * <p>This is the single owner of the client's volume data: the displayed/cached volumes, the
 * per-chunk position index, the authoritative snapshots used for delta/prediction rebasing, and
 * the authoritative fluid views. The facade keeps short aliases to these maps so its many call
 * sites are unchanged, but the canonical declarations (and any future accessors) live here.</p>
 *
 * <p>{@code VOLUMES} and {@code CLIENT_FLUIDS} are concurrent because terrain compilation workers
 * read them while the client thread writes; the rest are client-thread-only.</p>
 */
public final class MicrovoxelClientVolumeStore {
    private MicrovoxelClientVolumeStore() {
    }

    /** Displayed/predicted volumes. Read by terrain workers; authoritative writes on the client thread. */
    public static final Map<BlockPos, MicrovoxelCachedVolume> VOLUMES = new ConcurrentHashMap<>();

    /** Per-chunk position index, so rendering never walks the global volume map. */
    public static final Map<Long, Set<BlockPos>> CHUNKS = new HashMap<>();

    /** Server-authoritative volume snapshots, used as the base for predicted edits and deltas. */
    public static final Map<BlockPos, MicrovoxelVolume> AUTHORITATIVE_VOLUMES = new HashMap<>();

    /**
     * Authoritative fluid levels per volume, mirrored from the server (never simulated locally).
     * A missing entry means dry — or not yet arrived, in which case vanilla water covers the race.
     */
    public static final Map<BlockPos, MicrovoxelFluidView> CLIENT_FLUIDS = new ConcurrentHashMap<>();
}
