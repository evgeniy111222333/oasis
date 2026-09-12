package ua.rp.chat.microvoxel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sole writer of microvoxel projection markers.
 *
 * <p>The {@link MicrovoxelStore} is the single source of truth for volume data; this class is the
 * only component that may touch world blocks on behalf of microvoxels. Markers are a derived,
 * convergent projection: every store mutation is materialized/dematerialized here, and loaded
 * chunks are reconciled against the store so that neither missing markers nor orphan markers can
 * survive. Reconciliation is deferred by one server tick (C2ME can invoke CHUNK_LOAD while the
 * chunk is still being promoted) and runs at whole-chunk granularity, so it never produces the
 * old cross-chunk marker catch-up.</p>
 */
public final class MicrovoxelProjection {
    /** Whole chunks reconciled per tick. Chunk loading is itself rate-limited by the chunk system,
     *  so this cap cannot produce territory-scale catch-up. */
    public static final int MAX_RECONCILES_PER_TICK = 2;

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger("MicrovoxelProjection");

    /** The environment-facing surface of the projection. Unit tests substitute a fake surface. */
    public interface World {
        ServerLevel getWorld(UUID worldId);

        /** The currently loaded chunk, or {@code null} when absent. Never loads synchronously. */
        LevelChunk loadedChunk(UUID worldId, int chunkX, int chunkZ);

        void setBlock(ServerLevel world, LevelChunk chunk, BlockPos pos, BlockState state);

        void scheduleLight(ServerLevel world, BlockPos pos);
    }

    private final MicrovoxelStore store;
    private final World world;
    private final Runnable persistence;
    private final CoalescingWorkQueue<ChunkKey> reconcileQueue = new CoalescingWorkQueue<>();
    private boolean shuttingDown;

    /** Volumes removed while their chunk was unloaded; their markers are cleared on reconcile. */
    private final Set<MicrovoxelKey> clearedWhileUnloaded = ConcurrentHashMap.newKeySet();

    /**
     * Derives the full marker blockstate (light, sound, fluid flag) for one volume. Injected
     * so fluid awareness lives in the facade: every materialize path preserves the
     * waterlogged flag automatically instead of flickering water off on each edit.
     */
    public interface MarkerStates {
        BlockState markerState(MicrovoxelKey key, MicrovoxelVolume volume);
    }

    private final MarkerStates markerStates;

    public MicrovoxelProjection(MicrovoxelStore store, World world, Runnable persistence) {
        this(store, world, persistence, (key, volume) -> MicrovoxelManager.markerState(volume));
    }

    public MicrovoxelProjection(MicrovoxelStore store, World world, Runnable persistence,
                                MarkerStates markerStates) {
        if (store == null) throw new IllegalArgumentException("Projection requires a store");
        this.store = store;
        this.world = world;
        this.persistence = persistence;
        this.markerStates = markerStates == null
                ? (key, volume) -> MicrovoxelManager.markerState(volume)
                : markerStates;
    }

    public void beginShutdown() {
        shuttingDown = true;
    }

    /**
     * Writes the volume into the store and projects its marker into the loaded world/lighting.
     * Any stale unload-time tombstone for this key is cleared first, so recreating a volume
     * on an unloaded chunk can never be deleted by its own outdated clear marker.
     */
    public void materialize(MicrovoxelKey key, MicrovoxelVolume volume) {
        if (volume == null) throw new IllegalArgumentException("Cannot materialize a null volume");
        // Single-writer invariant: an empty volume is indistinguishable from "no volume", so it
        // must project as a removal. Without this an empty volume would leave an unbreakable
        // marker with nothing inside, which no normal attack path can clean up.
        if (volume.occupiedCount() == 0) {
            dematerialize(key);
            return;
        }
        clearedWhileUnloaded.remove(key);
        store.put(key, volume);
        store.markDirty(key);
        persistence.run();
        ServerLevel level = world.getWorld(key.worldId());
        if (level == null) return;
        LevelChunk chunk = world.loadedChunk(key.worldId(), key.chunkX(), key.chunkZ());
        if (chunk == null) {
            reconcileQueue.schedule(ChunkKey.of(key));
            return;
        }
        BlockPos pos = pos(key);
        BlockState desired = markerStates.markerState(key, volume);
        if (!chunk.getBlockState(pos).equals(desired)) {
            world.setBlock(level, chunk, pos, desired);
            world.scheduleLight(level, pos);
        }
    }

    /** Removes the volume from the store and clears its marker (deferred if the chunk is away). */
    public void dematerialize(MicrovoxelKey key) {
        store.remove(key);
        store.markDirty(key);
        persistence.run();
        ServerLevel level = world.getWorld(key.worldId());
        if (level == null) return;
        LevelChunk chunk = world.loadedChunk(key.worldId(), key.chunkX(), key.chunkZ());
        if (chunk == null) {
            clearedWhileUnloaded.add(key);
            reconcileQueue.schedule(ChunkKey.of(key));
            return;
        }
        clearMarker(level, chunk, key);
    }

    /** Replaces a removed volume with an ordinary block (used by "restore volume"). */
    public void replaceWithBlock(MicrovoxelKey key, BlockState state) {
        store.remove(key);
        store.markDirty(key);
        persistence.run();
        ServerLevel level = world.getWorld(key.worldId());
        if (level == null) return;
        LevelChunk chunk = world.loadedChunk(key.worldId(), key.chunkX(), key.chunkZ());
        if (chunk == null) {
            clearedWhileUnloaded.add(key);
            reconcileQueue.schedule(ChunkKey.of(key));
            return;
        }
        BlockPos pos = pos(key);
        if (!chunk.getBlockState(pos).equals(state)) {
            world.setBlock(level, chunk, pos, state);
            world.scheduleLight(level, pos);
        }
    }

    /** Removes a stray marker that has no authoritative volume (client-triggered self-heal). */
    public void clearOrphanMarker(MicrovoxelKey key) {
        ServerLevel level = world.getWorld(key.worldId());
        if (level == null) return;
        LevelChunk chunk = world.loadedChunk(key.worldId(), key.chunkX(), key.chunkZ());
        if (chunk == null) return;
        clearMarker(level, chunk, key);
    }

    /** Re-lays a marker without touching the store (creative break protection path). */
    public void ensureMarker(MicrovoxelKey key, MicrovoxelVolume volume) {
        ServerLevel level = world.getWorld(key.worldId());
        if (level == null) return;
        LevelChunk chunk = world.loadedChunk(key.worldId(), key.chunkX(), key.chunkZ());
        if (chunk == null) return;
        BlockPos pos = pos(key);
        BlockState desired = markerStates.markerState(key, volume);
        if (!chunk.getBlockState(pos).equals(desired)) {
            world.setBlock(level, chunk, pos, desired);
            world.scheduleLight(level, pos);
        }
    }

    /** Deferred whole-chunk reconciliation requested by a chunk-load or store mutation. */
    public void scheduleReconcile(ChunkKey chunkKey) {
        if (chunkKey != null) reconcileQueue.schedule(chunkKey);
    }

    /** Schedules every loaded chunk that the store indexes (startup self-heal). */
    public void reconcileLoadedChunks() {
        for (ChunkKey chunkKey : store.indexedChunks()) {
            ServerLevel level = world.getWorld(chunkKey.worldId());
            if (level != null
                    && world.loadedChunk(chunkKey.worldId(), chunkKey.x(), chunkKey.z()) != null) {
                reconcileQueue.schedule(chunkKey);
            }
        }
    }

    public int pendingReconcileChunks() {
        return reconcileQueue.scheduledCount();
    }

    /** Called at the end of a server tick; drains whole-chunk reconciles without a marker budget. */
    public void tick() {
        if (shuttingDown) return;
        int budget = MAX_RECONCILES_PER_TICK;
        while (budget-- > 0) {
            ChunkKey chunkKey = reconcileQueue.poll();
            if (chunkKey == null) return;
            ServerLevel level = world.getWorld(chunkKey.worldId());
            LevelChunk chunk = (level == null)
                    ? null
                    : world.loadedChunk(chunkKey.worldId(), chunkKey.x(), chunkKey.z());
            if (level == null || chunk == null) {
                reconcileQueue.complete(chunkKey);
                continue;
            }
            try {
                reconcileChunk(level, chunk, chunkKey);
            } catch (RuntimeException failure) {
                // A world/light write failure must not permanently lease this chunk:
                // release it so a later tick can retry, and keep the failure from
                // escaping into the server tick loop.
                LOGGER.warning("Microvoxel chunk reconcile failed for "
                        + chunkKey.worldId() + " " + chunkKey.x() + "," + chunkKey.z()
                        + ": " + failure.getMessage());
            } finally {
                reconcileQueue.complete(chunkKey);
            }
        }
    }

    private void reconcileChunk(ServerLevel level, LevelChunk chunk, ChunkKey chunkKey) {
        java.util.Set<MicrovoxelKey> stored = new java.util.HashSet<>();
        java.util.List<MicrovoxelKey> emptyShells = new java.util.ArrayList<>();
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                : store.inChunk(chunkKey.worldId(), chunkKey.x(), chunkKey.z())) {
            MicrovoxelKey key = entry.getKey();
            if (entry.getValue().occupiedCount() == 0) {
                // A stale empty shell (e.g. loaded from an old store): drop it and clear any
                // marker instead of re-projecting an unbreakable, empty block.
                emptyShells.add(key);
                continue;
            }
            stored.add(key);
            BlockPos pos = pos(key);
            BlockState desired = markerStates.markerState(key, entry.getValue());
            if (!chunk.getBlockState(pos).equals(desired)) {
                world.setBlock(level, chunk, pos, desired);
                world.scheduleLight(level, pos);
            }
        }
        for (MicrovoxelKey key : emptyShells) {
            store.remove(key);
            store.markDirty(key);
            persistence.run();
            clearMarker(level, chunk, key);
        }
        Iterator<MicrovoxelKey> clears = clearedWhileUnloaded.iterator();
        while (clears.hasNext()) {
            MicrovoxelKey key = clears.next();
            if (key.worldId().equals(chunkKey.worldId())
                    && key.chunkX() == chunkKey.x()
                    && key.chunkZ() == chunkKey.z()) {
                if (!stored.contains(key)) clearMarker(level, chunk, key);
                clears.remove();
            }
        }
    }

    private void clearMarker(ServerLevel level, LevelChunk chunk, MicrovoxelKey key) {
        BlockPos pos = pos(key);
        BlockState current = chunk.getBlockState(pos);
        if (MicrovoxelBlocks.isMarker(current)) {
            world.setBlock(level, chunk, pos, Blocks.AIR.defaultBlockState());
            world.scheduleLight(level, pos);
        }
    }

    private static BlockPos pos(MicrovoxelKey key) {
        return new BlockPos(key.x(), key.y(), key.z());
    }
}