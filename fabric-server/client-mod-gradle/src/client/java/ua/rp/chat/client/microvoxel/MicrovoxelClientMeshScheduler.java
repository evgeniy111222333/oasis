package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelMeshBackpressure;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client mesh scheduler, extracted from {@link MicrovoxelClientState}: the mesh work queue, the
 * in-flight job ledger (near/mid/far tiers), the delayed boundary rebuilds, the chunk-batch and
 * section-rebuild queues, and the Carver work-focus hold/flush mechanism.
 *
 * <p>Compiles frozen volume snapshots off the client thread via {@link MicrovoxelGreedyMesher} and
 * publishes the result back on the game thread. The facade owns the displayed volume record and
 * the chunk index; this class only schedules work and installs meshes through
 * {@link MicrovoxelClientState}.</p>
 */
public final class MicrovoxelClientMeshScheduler {
    private static final long MESH_BUDGET_NANOS = 3_000_000L;
    private static final long CHUNK_BATCH_BUDGET_NANOS = 1_500_000L;
    private static final Set<BlockPos> MESH_QUEUE = new LinkedHashSet<>();
    private static final Map<BlockPos, MeshJob> MESHING_JOBS = new HashMap<>();
    private static final Set<BlockPos> MESH_DIRTY_DURING_BUILD = new HashSet<>();
    private static final Map<BlockPos, Integer> DELAYED_BOUNDARY_REBUILDS = new HashMap<>();
    private static final Map<Long, MicrovoxelClientState.ChunkBatch> CHUNK_BATCHES = new HashMap<>();
    private static final Set<Long> CHUNK_BATCH_QUEUE = new LinkedHashSet<>();
    private static final Set<Long> SECTION_REBUILD_QUEUE = new LinkedHashSet<>();
    private static final int MAX_SECTION_REBUILDS_PER_TICK = 8;
    /**
     * Hard cap on mesh builds in flight. Bounds both the worker backlog and the client-thread
     * snapshot cost per tick (each build clones up to seven volumes); without it a burst of edits
     * could submit hundreds of jobs in a single tick. Deferred positions stay queued and are
     * retried next tick.
     */
    private static final int MAX_INFLIGHT_MESH_JOBS = 16;

    private static final ExecutorService MESHING_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), runnable -> {
                Thread thread = new Thread(runnable, "OasisMesher");
                thread.setDaemon(true);
                return thread;
            });

    // --- Carver work focus: hold section/mesh updates and flush on work-phase crossings. ---
    private static BlockPos workFocus;
    private static final Set<Long> HELD_WORK_SECTIONS = new LinkedHashSet<>();
    private static final Set<BlockPos> PENDING_WORK_MESH = new LinkedHashSet<>();
    private static int lastWorkFlushTick;

    private MicrovoxelClientMeshScheduler() {
    }

    /** One scheduled build: the generation and revision it was queued for, plus the LOD stride. */
    private record MeshJob(int generation, int revision, int stride) {
    }

    /** Shared with the LOD review. */
    static void enqueueMesh(BlockPos position) {
        MESH_QUEUE.add(position);
    }

    static int queuedMeshCount() {
        return MESH_QUEUE.size();
    }

    /**
     * Builds one volume's mesh off-thread. A job already in flight marks the position dirty and
     * returns; the completion callback re-queues it so intermediate edits are never lost.
     */
    static void rebuild(BlockPos position) {
        BlockPos immutablePos = position.immutable();
        MicrovoxelCachedVolume center = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos);
        if (center == null) return;

        int stride = center.volume.hasGeometry()
                ? 1 : ua.rp.chat.microvoxel.MicrovoxelLodTiers.strideFor(center.lodTier);
        MeshJob job = new MeshJob(MicrovoxelClientState.stateGeneration(), center.volume.revision(), stride);
        if (MESHING_JOBS.putIfAbsent(immutablePos, job) != null) {
            MESH_DIRTY_DURING_BUILD.add(immutablePos);
            return;
        }

        MicrovoxelCachedVolume downVol = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos.below());
        MicrovoxelCachedVolume upVol = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos.above());
        MicrovoxelCachedVolume northVol = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos.north());
        MicrovoxelCachedVolume southVol = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos.south());
        MicrovoxelCachedVolume westVol = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos.west());
        MicrovoxelCachedVolume eastVol = MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos.east());

        var level = MicrovoxelClientState.activeLevel();
        boolean downSolid = (downVol == null && level != null) && level.getBlockState(immutablePos.below()).isSolidRender();
        boolean upSolid = (upVol == null && level != null) && level.getBlockState(immutablePos.above()).isSolidRender();
        boolean northSolid = (northVol == null && level != null) && level.getBlockState(immutablePos.north()).isSolidRender();
        boolean southSolid = (southVol == null && level != null) && level.getBlockState(immutablePos.south()).isSolidRender();
        boolean westSolid = (westVol == null && level != null) && level.getBlockState(immutablePos.west()).isSolidRender();
        boolean eastSolid = (eastVol == null && level != null) && level.getBlockState(immutablePos.east()).isSolidRender();

        MicrovoxelVolume centerVol = center.volume.copy();
        MicrovoxelVolume downVolSnap = downVol != null ? downVol.volume.copy() : null;
        MicrovoxelVolume upVolSnap = upVol != null ? upVol.volume.copy() : null;
        MicrovoxelVolume northVolSnap = northVol != null ? northVol.volume.copy() : null;
        MicrovoxelVolume southVolSnap = southVol != null ? southVol.volume.copy() : null;
        MicrovoxelVolume westVolSnap = westVol != null ? westVol.volume.copy() : null;
        MicrovoxelVolume eastVolSnap = eastVol != null ? eastVol.volume.copy() : null;

        final int jobStride = stride;
        MESHING_EXECUTOR.submit(() -> {
            try {
                long meshStart = System.nanoTime();
                MicrovoxelGreedyMesher.NeighbourLookup neighbours = (x, y, z) -> {
                    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
                        return centerVol.materialAt(x, y, z);
                    }
                    int ox = Math.floorDiv(x, 16);
                    int oy = Math.floorDiv(y, 16);
                    int oz = Math.floorDiv(z, 16);
                    MicrovoxelVolume neighbourVol = null;
                    boolean neighbourSolid = false;
                    if (ox == -1) {
                        neighbourVol = westVolSnap;
                        neighbourSolid = westSolid;
                    } else if (ox == 1) {
                        neighbourVol = eastVolSnap;
                        neighbourSolid = eastSolid;
                    } else if (oy == -1) {
                        neighbourVol = downVolSnap;
                        neighbourSolid = downSolid;
                    } else if (oy == 1) {
                        neighbourVol = upVolSnap;
                        neighbourSolid = upSolid;
                    } else if (oz == -1) {
                        neighbourVol = northVolSnap;
                        neighbourSolid = northSolid;
                    } else if (oz == 1) {
                        neighbourVol = southVolSnap;
                        neighbourSolid = southSolid;
                    }
                    if (neighbourVol != null) {
                        return neighbourVol.materialAt(
                                Math.floorMod(x, MicrovoxelVolume.RESOLUTION),
                                Math.floorMod(y, MicrovoxelVolume.RESOLUTION),
                                Math.floorMod(z, MicrovoxelVolume.RESOLUTION));
                    }
                    return neighbourSolid ? 1 : 0;
                };
                List<MicrovoxelGreedyMesher.Face> mesh = center.volume.hasGeometry()
                        ? MicrovoxelGreedyMesher.build(centerVol, neighbours,
                                cell -> centerVol.shapeAt(cell) != 0)
                        : MicrovoxelGreedyMesher.build(centerVol, neighbours, jobStride);
                MicrovoxelClientMetrics.inc("mesh.jobs");
                MicrovoxelClientMetrics.add("mesh.us", (System.nanoTime() - meshStart) / 1000L);

                Minecraft.getInstance().execute(() -> {
                    if (!MESHING_JOBS.remove(immutablePos, job)) return;
                    MicrovoxelCachedVolume currentCenter = job.generation() == MicrovoxelClientState.stateGeneration()
                            ? MicrovoxelClientVolumeStore.VOLUMES.get(immutablePos) : null;
                    boolean current = currentCenter != null && currentCenter.volume.revision() == job.revision();
                    if (currentCenter != null && job.generation() == MicrovoxelClientState.stateGeneration()) {
                        // A continuously edited volume may already be one revision ahead. Showing
                        // this completed intermediate mesh is still strictly better than freezing
                        // the old geometry until the mouse button is released. Only one job per
                        // position exists, so intermediate results remain monotonically ordered.
                        // All three tiers cache side by side so LOD flips never trigger a fresh
                        // background build on their own.
                        if (job.stride() == 1) {
                            currentCenter.mesh = mesh;
                            currentCenter.meshRevision = job.revision();
                        } else if (job.stride() == 2) {
                            currentCenter.midMesh = mesh;
                            currentCenter.midMeshRevision = job.revision();
                        } else {
                            currentCenter.farMesh = mesh;
                            currentCenter.farMeshRevision = job.revision();
                        }
                        queueChunkBatch(immutablePos);
                    }
                    if (job.generation() == MicrovoxelClientState.stateGeneration()
                            && (MESH_DIRTY_DURING_BUILD.remove(immutablePos) || !current)) {
                        requeueMesh(immutablePos);
                    }
                });
            } catch (Exception error) {
                EclipseClientMod.LOGGER.error("Failed background meshing for " + immutablePos, error);
                Minecraft.getInstance().execute(() -> {
                    if (!MESHING_JOBS.remove(immutablePos, job)) return;
                    if (job.generation() == MicrovoxelClientState.stateGeneration()
                            && MESH_DIRTY_DURING_BUILD.remove(immutablePos)) {
                        requeueMesh(immutablePos);
                    }
                });
            }
        });
    }

    static boolean changesTouchBoundary(MicrovoxelVolume oldVolume, MicrovoxelVolume newVolume) {
        if (oldVolume == null || newVolume == null) return true;
        // Zero-copy shell comparison; the old double-clone price is gone.
        return oldVolume.boundaryDiffersFrom(newVolume);
    }

    static void enqueueChunkBatch(long chunkKey) {
        CHUNK_BATCH_QUEUE.add(chunkKey);
    }

    static int sectionQueueSize() {
        return SECTION_REBUILD_QUEUE.size();
    }

    static void queueRebuild(BlockPos position) {
        MESH_QUEUE.add(position.immutable());
        queueNeighborsRebuild(position);
    }

    private static void queueNeighborsRebuild(BlockPos position) {
        MESH_QUEUE.add(position.offset(1, 0, 0));
        MESH_QUEUE.add(position.offset(-1, 0, 0));
        MESH_QUEUE.add(position.offset(0, 1, 0));
        MESH_QUEUE.add(position.offset(0, -1, 0));
        MESH_QUEUE.add(position.offset(0, 0, 1));
        MESH_QUEUE.add(position.offset(0, 0, -1));
    }

    private static void scheduleBoundaryRebuild(BlockPos position) {
        DELAYED_BOUNDARY_REBUILDS.put(position.immutable(), MicrovoxelClientState.tick() + 2);
    }

    private static void processDelayedBoundaryRebuilds() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = DELAYED_BOUNDARY_REBUILDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() > MicrovoxelClientState.tick()) continue;
            queueRebuild(entry.getKey());
            iterator.remove();
        }
    }

    static void clearWorkFocusIf(BlockPos position) {
        if (workFocus != null && workFocus.equals(position.immutable())) setWorkFocus(null);
    }

    static void queueChunkBatch(BlockPos position) {
        if (Boolean.getBoolean("rpchat.microvoxel.legacyRenderer")) {
            CHUNK_BATCH_QUEUE.add(MicrovoxelClientState.chunkKey(position.getX() >> 4, position.getZ() >> 4));
        }
        if (workFocus != null && workFocus.equals(position.immutable())) {
            HELD_WORK_SECTIONS.add(SectionPos.asLong(
                    position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4));
            MicrovoxelClientMetrics.inc("section.workheld");
            return;
        }
        SECTION_REBUILD_QUEUE.add(SectionPos.asLong(
                position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4));
    }

    /** Public entry points used by the facade's install path. */
    static void onDisplayedRemoved(BlockPos position) {
        queueRebuild(position);
        queueChunkBatch(position);
        scheduleBoundaryRebuild(position);
    }

    static void onDisplayedChanged(BlockPos position, boolean boundaryChanged) {
        MicrovoxelClientLod.requestRebuild(position);
        if (boundaryChanged) {
            queueNeighborsRebuild(position);
            scheduleBoundaryRebuild(position);
        }
    }

    static void onFluidChanged(BlockPos position) {
        queueChunkBatch(position);
        scheduleBoundaryRebuild(position);
    }

    static void onCracksChanged(BlockPos position) {
        queueRebuild(position);
        queueChunkBatch(position);
    }

    /**
     * Carving work focus for throttled section rebuilds. While set, render sections covering the
     * focused volume are held back and flushed on work-phase crossings instead of every applied
     * slice. Clearing the focus always flushes first, so no update can ever strand.
     */
    public static void setWorkFocus(BlockPos focus) {
        if (focus == null && workFocus == null) return;
        workFocus = focus == null ? null : focus.immutable();
        if (focus == null) {
            flushWorkFocus();
        }
    }

    /** Moves held focus sections and coalesced mesh jobs back to the live queues. */
    public static void flushWorkFocus() {
        if (!HELD_WORK_SECTIONS.isEmpty()) {
            MicrovoxelClientMetrics.add("section.workflushes", HELD_WORK_SECTIONS.size());
            SECTION_REBUILD_QUEUE.addAll(HELD_WORK_SECTIONS);
            HELD_WORK_SECTIONS.clear();
        }
        if (!PENDING_WORK_MESH.isEmpty()) {
            MESH_QUEUE.addAll(PENDING_WORK_MESH);
            PENDING_WORK_MESH.clear();
        }
        lastWorkFlushTick = MicrovoxelClientState.tick();
    }

    /** Coalesced mesh requeue: focus volumes wait for the next phase flush. */
    private static void requeueMesh(BlockPos position) {
        if (workFocus != null && workFocus.equals(position)) {
            PENDING_WORK_MESH.add(position);
            MicrovoxelClientMetrics.inc("mesh.workcoalesced");
            return;
        }
        MESH_QUEUE.add(position);
    }

    /** Fail-safe: a stalled work session flushes held updates within ~3 seconds. */
    static void drainWorkFocusFailsafe() {
        if (workFocus == null) return;
        if ((!HELD_WORK_SECTIONS.isEmpty() || !PENDING_WORK_MESH.isEmpty())
                && MicrovoxelClientState.tick() - lastWorkFlushTick > 60) {
            flushWorkFocus();
        }
    }

    /** Per-tick pump: delayed boundaries, then bounded section/mesh/batch rebuilds. */
    static void tick(Minecraft minecraft) {
        processDelayedBoundaryRebuilds();
        drainWorkFocusFailsafe();
        rebuildQueuedSections(minecraft);
        rebuildQueuedMeshes();
        rebuildQueuedChunkBatches();
    }

    private static void rebuildQueuedSections(Minecraft minecraft) {
        if (minecraft.level == null) {
            SECTION_REBUILD_QUEUE.clear();
            return;
        }
        Iterator<Long> iterator = SECTION_REBUILD_QUEUE.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && rebuilt < MAX_SECTION_REBUILDS_PER_TICK) {
            long section = iterator.next();
            iterator.remove();
            minecraft.levelRenderer.setSectionDirty(
                    SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
            rebuilt++;
        }
        if (rebuilt > 0) MicrovoxelClientMetrics.add("section.rebuilds", rebuilt);
    }

    private static void rebuildQueuedMeshes() {
        // Backpressure first: cap in-flight jobs so the worker queue (and the per-job client-thread
        // snapshots) cannot spike. If the pool is saturated, leave the queue intact for next tick.
        int admits = MicrovoxelMeshBackpressure.admitCount(
                MESHING_JOBS.size(), MESH_QUEUE.size(), MAX_INFLIGHT_MESH_JOBS);
        if (admits <= 0) return;
        long deadline = System.nanoTime() + MESH_BUDGET_NANOS;
        Iterator<BlockPos> iterator = MESH_QUEUE.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && rebuilt < admits
                && (rebuilt == 0 || System.nanoTime() < deadline)) {
            BlockPos position = iterator.next();
            iterator.remove();
            rebuild(position);
            rebuilt++;
        }
    }

    private static void rebuildQueuedChunkBatches() {
        if (!Boolean.getBoolean("rpchat.microvoxel.legacyRenderer")) {
            CHUNK_BATCH_QUEUE.clear();
            CHUNK_BATCHES.clear();
            return;
        }
        long deadline = System.nanoTime() + CHUNK_BATCH_BUDGET_NANOS;
        Iterator<Long> iterator = CHUNK_BATCH_QUEUE.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && (rebuilt == 0 || System.nanoTime() < deadline)) {
            long key = iterator.next();
            iterator.remove();
            Set<BlockPos> positions = MicrovoxelClientVolumeStore.CHUNKS.get(key);
            if (positions == null || positions.isEmpty()) {
                CHUNK_BATCHES.remove(key);
                rebuilt++;
                continue;
            }
            List<MicrovoxelClientState.ChunkFace> faces = new ArrayList<>();
            for (BlockPos position : positions) {
                MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position);
                if (cached == null) continue;
                for (MicrovoxelGreedyMesher.Face face : cached.mesh) {
                    faces.add(new MicrovoxelClientState.ChunkFace(position, cached, face));
                }
            }
            CHUNK_BATCHES.put(key, new MicrovoxelClientState.ChunkBatch((int) (key >> 32), (int) key, faces));
            rebuilt++;
        }
    }

    /** Volumes whose cached volume overlaps the horizontal radius. */
    public static Collection<Map.Entry<BlockPos, MicrovoxelCachedVolume>> volumesNear(
            double blockX, double blockZ, double radius) {
        int minChunkX = MicrovoxelClientState.floorChunk(blockX - radius);
        int maxChunkX = MicrovoxelClientState.floorChunk(blockX + radius);
        int minChunkZ = MicrovoxelClientState.floorChunk(blockZ - radius);
        int maxChunkZ = MicrovoxelClientState.floorChunk(blockZ + radius);
        List<Map.Entry<BlockPos, MicrovoxelCachedVolume>> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (BlockPos position : MicrovoxelClientVolumeStore.CHUNKS
                        .getOrDefault(MicrovoxelClientState.chunkKey(chunkX, chunkZ), Set.of())) {
                    MicrovoxelCachedVolume volume = MicrovoxelClientVolumeStore.VOLUMES.get(position);
                    if (volume != null) result.add(Map.entry(position, volume));
                }
            }
        }
        return result;
    }

    /** Immutable chunk-level render batches overlapping the requested horizontal radius. */
    public static Collection<MicrovoxelClientState.ChunkBatch> batchesNear(
            double blockX, double blockZ, double radius) {
        int minChunkX = MicrovoxelClientState.floorChunk(blockX - radius);
        int maxChunkX = MicrovoxelClientState.floorChunk(blockX + radius);
        int minChunkZ = MicrovoxelClientState.floorChunk(blockZ - radius);
        int maxChunkZ = MicrovoxelClientState.floorChunk(blockZ + radius);
        List<MicrovoxelClientState.ChunkBatch> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                MicrovoxelClientState.ChunkBatch batch =
                        CHUNK_BATCHES.get(MicrovoxelClientState.chunkKey(chunkX, chunkZ));
                if (batch != null && !batch.faces().isEmpty()) result.add(batch);
            }
        }
        result.sort(java.util.Comparator.comparingDouble(
                batch -> batch.distanceSquaredTo(blockX, blockZ)));
        return result;
    }

    static void clear() {
        MESH_QUEUE.clear();
        MESHING_JOBS.clear();
        MESH_DIRTY_DURING_BUILD.clear();
        DELAYED_BOUNDARY_REBUILDS.clear();
        CHUNK_BATCHES.clear();
        CHUNK_BATCH_QUEUE.clear();
        SECTION_REBUILD_QUEUE.clear();
        HELD_WORK_SECTIONS.clear();
        PENDING_WORK_MESH.clear();
        workFocus = null;
    }
}
