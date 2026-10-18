package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelGeometry;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelMeshBackpressure;
import ua.rp.chat.microvoxel.MicrovoxelPrediction;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelSessionToken;
import ua.rp.chat.microvoxel.MicrovoxelShape;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.MicrovoxelWire;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MicrovoxelClientState {
    private static final boolean DEBUG = Boolean.getBoolean("rpchat.microvoxel.debug");
    /** Wire major. Frame layout, codes and codecs are the shared MicrovoxelWire contract. */
    public static final int PROTOCOL_VERSION = MicrovoxelWire.MAJOR;
    private static final int CLEAR = MicrovoxelWire.CLEAR;
    private static final int UPSERT = MicrovoxelWire.UPSERT;
    private static final int REMOVE = MicrovoxelWire.REMOVE;
    private static final int MESSAGE = MicrovoxelWire.MESSAGE;
    private static final int BATCH_UPSERT = MicrovoxelWire.BATCH_UPSERT;
    private static final int CLEAR_CHUNK = MicrovoxelWire.CLEAR_CHUNK;
    private static final int DELTA_UPSERT = MicrovoxelWire.DELTA_UPSERT;
    private static final int TRANSACTION = MicrovoxelWire.TRANSACTION;
    private static final int EDIT_RESULT = MicrovoxelWire.EDIT_RESULT;
    private static final int SNAPSHOT_BEGIN = MicrovoxelWire.SNAPSHOT_BEGIN;
    private static final int SNAPSHOT_END = MicrovoxelWire.SNAPSHOT_END;
    private static final int MINE_STAGE = MicrovoxelWire.MINE_STAGE;
    private static final int FLUID_UPSERT = MicrovoxelWire.FLUID_UPSERT;
    private static final int FLUID_REMOVE = MicrovoxelWire.FLUID_REMOVE;
    private static final int HELLO_ACK = MicrovoxelWire.HELLO_ACK;
    private static final int ACTION_READY = MicrovoxelWire.ACTION_READY;
    private static final int ACTION_RESYNC_VOLUME = MicrovoxelWire.ACTION_RESYNC_VOLUME;
    private static final int ACTION_RESYNC_CHUNK = MicrovoxelWire.ACTION_RESYNC_CHUNK;
    private static final int ACTION_SNAPSHOT_ACK = MicrovoxelWire.ACTION_SNAPSHOT_ACK;
    private static final int ACTION_HELLO = MicrovoxelWire.ACTION_HELLO;
    /** Capabilities the server confirmed (intersection of both advertisements). */
    private static int negotiatedCapabilities = MicrovoxelWire.CLIENT_CAPABILITIES;
    /**
     * Capabilities actually sent in HELLO. The crack overlay flag can drop {@code CAP_MINE_STAGE}
     * at runtime, so the server never sends MINE_STAGE frames a disabled client cannot draw.
     */
    private static int advertisedCapabilities() {
        return MicrovoxelClientCracks.overlayEnabled()
                ? MicrovoxelWire.CLIENT_CAPABILITIES
                : MicrovoxelWire.CLIENT_CAPABILITIES & ~MicrovoxelWire.CAP_MINE_STAGE;
    }
    private static final int READY_RETRY_TICKS = 60;
    private static final Map<BlockPos, Integer> PENDING_PLACEMENTS = new HashMap<>();
    private static final int PLACEMENT_PREDICTION_TIMEOUT_TICKS = 60;
    /**
     * Authoritative fluid levels per volume, mirrored from the server (never simulated
     * locally). Read by terrain compilation workers; writes stay on the client thread.
     * A missing entry means dry вЂ” or not yet arrived, in which case vanilla water (when
     * the marker is waterlogged) covers the race until the packet lands.
     */

    private static final int MAX_BATCH_ENTRIES = 4096;
    /**
     * One-shot runtime evidence for the microvoxel pipeline.  This is intentionally keyed by
     * volume revision: a diagnostic session stays compact while still proving which shape and
     * material were used for every edited state the player actually sees.
     */
    private static final Set<String> PROBE_EMITTED = new HashSet<>();
    /**
     * Terrain compilation can discover a native marker before its ordered volume packet arrives.
     * Compilation workers only enqueue immutable positions; all networking stays on the client
     * thread in {@link #clientTick(Minecraft)}.
     */
    private static ClientLevel activeLevel;
    private static int clientTick;
    private static int stateGeneration;
    private static boolean snapshotConfirmed;
    private static int nextReadyTick;
    private static long activeSnapshotId = -1L;
    private static int lastResyncRequestTick = Integer.MIN_VALUE / 2;
    private static long nextTransactionId = 1L;
    /**
     * Per-login anti-replay token from the server's {@code HELLO_ACK}. Replay-sensitive actions
     * echo it; {@code 0} means "not handshaken yet" and blocks such actions locally.
     */
    private static volatile long sessionNonce;


    // --- Accessors for the extracted sync dispatcher (MicrovoxelClientSync) ---
    static void setNegotiatedCapabilities(int capabilities) {
        negotiatedCapabilities = capabilities;
    }

    static void setSessionNonce(long nonce) {
        sessionNonce = nonce;
    }

    static long activeSnapshotId() {
        return activeSnapshotId;
    }

    static void setActiveSnapshotId(long snapshotId) {
        activeSnapshotId = snapshotId;
    }

    static void setSnapshotConfirmed(boolean confirmed) {
        snapshotConfirmed = confirmed;
    }

    static boolean debug() {
        return DEBUG;
    }

    static int maxBatchEntries() {
        return MAX_BATCH_ENTRIES;
    }

    /** Ready-retry cadence, shared with the marker-reconciliation retry budget. */
    static int readyRetryTicks() {
        return READY_RETRY_TICKS;
    }

    /** Active client level, shared with the mesh scheduler. */
    static ClientLevel activeLevel() {
        return activeLevel;
    }

    /** Generation counter bumped on every level switch, shared with the mesh scheduler. */
    static int stateGeneration() {
        return stateGeneration;
    }
    private MicrovoxelClientState() {
    }

    /** Current client tick, shared with the extracted collaborators (LOD review, prediction). */
    static int tick() {
        return clientTick;
    }

    /** Enqueues a volume for mesh rebuild; shared with the LOD review. */
    static void enqueueMesh(BlockPos position) {
        MicrovoxelClientMeshScheduler.enqueueMesh(position);
    }

    public static void clientTick(Minecraft minecraft) {
        clientTick++;
        if (minecraft.level != activeLevel) {
            activeLevel = minecraft.level;
            stateGeneration++;
            clearVolumes();
            MicrovoxelClientRenderer.clearMaterialCache();
            snapshotConfirmed = false;
            activeSnapshotId = -1L;
            sessionNonce = 0L;
            nextReadyTick = clientTick;
        }
        if (activeLevel != null && !snapshotConfirmed && clientTick >= nextReadyTick
                && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE)) {
            // Capability handshake first: major/minor/caps ride the x/y/z ints of the hello action.
            sendControlAction(ACTION_HELLO, nextTransactionId++,
                    MicrovoxelWire.MAJOR, MicrovoxelWire.MINOR, MicrovoxelWire.CLIENT_CAPABILITIES);
            sendControlAction(ACTION_READY, nextTransactionId++, 0, 0, 0);
            nextReadyTick = clientTick + READY_RETRY_TICKS;
        }
        processMissingMarkers();
        processPendingPlacements();
        MicrovoxelClientPrediction.expirePendingEdits();
        reconcileFluidPrediction(minecraft);
        drainLightChecks(minecraft);
        MicrovoxelClientLod.tick();
        // One client metrics line per minute at 20 TPS, correlatable with the server line.
        // Queue depths ride along every time: sustained growth here means meshing or section
        // rebuilds are not keeping up and LOD/mesh budgets need retuning.
        if (activeLevel != null && clientTick % 1200 == 0) {
            EclipseClientMod.LOGGER.info(MicrovoxelClientMetrics.summarize()
                    + " meshQueue=" + MicrovoxelClientMeshScheduler.queuedMeshCount()
                    + " sectionQueue=" + MicrovoxelClientMeshScheduler.sectionQueueSize()
                    + " volumes=" + MicrovoxelClientVolumeStore.VOLUMES.size()
                    + " pending=" + MicrovoxelClientPrediction.pendingCount());
            if (MicrovoxelClientMeshScheduler.queuedMeshCount() > 512
                    || MicrovoxelClientMeshScheduler.sectionQueueSize() > 64) {
                EclipseClientMod.LOGGER.warn(
                        "[MICROVOXEL] Rebuild backlog is growing: meshes={}, sections={}. "
                                + "Editing throughput exceeds background budgets.",
                        MicrovoxelClientMeshScheduler.queuedMeshCount(),
                        MicrovoxelClientMeshScheduler.sectionQueueSize());
            }
        }
        MicrovoxelClientMeshScheduler.tick(minecraft);
    }

    public static void handle(MicrovoxelSyncPayload payload) {
        MicrovoxelClientSync.handle(payload);
    }

    /**
     * Settles one transaction by id. The authoritative volume becomes the new replay base;
     * every still-pending operation (including other cells of the same brush stroke) is
     * replayed on top, so a reject rolls back exactly one transaction and never wipes later
     * clicks. Brush results settle every volume the stroke touched.
     */
    public static boolean isPending(long transactionId) {
        return MicrovoxelClientPrediction.isPending(transactionId);
    }

    static void acceptAuthoritative(BlockPos position, MicrovoxelVolume authoritative) {
        BlockPos immutable = position.immutable();
        // A crack on a cell the server just removed (broken) must not linger; the -1 frame is
        // best-effort, the authoritative volume is the source of truth.
        if (authoritative == null) clearCracks(immutable);
        else pruneCracks(immutable, authoritative);
        if (authoritative != null) MicrovoxelClientMarkers.remove(immutable);
        PENDING_PLACEMENTS.remove(immutable);
        if (MicrovoxelClientPrediction.hasPendingAt(immutable)) {
            if (authoritative == null) MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.remove(immutable);
            else MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.put(immutable, authoritative.copy());
            MicrovoxelClientPrediction.rebuildPredicted(immutable);
        } else {
            replaceDisplayed(immutable, authoritative);
        }
    }
    /**
     * Live geometry provider behind the section model seam. LOD and render-pass flags are
     * resolved here so compilation workers never branch on state internals.
     */
    public static MicrovoxelGeometryProvider geometryProvider() {
        return GeometryProviderHolder.INSTANCE;
    }

    private enum GeometryProviderHolder implements MicrovoxelGeometryProvider {
        INSTANCE;

        @Override
        public List<MicrovoxelGreedyMesher.Face> meshFor(BlockPos position) {
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
            if (cached == null) return List.of();
            if (cached.volume.hasGeometry()) {
                // Shaped cells are rendered by the section model, not the full-cube greedy mesh,
                // so a shaped volume always uses its exact stride-1 mesh and ignores LOD tiers to
                // avoid a coarse full-cube mesh double-drawing the shapes.
                return cached.mesh;
            }
            int revision = cached.volume.revision();
            if (cached.lodTier == ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.FAR
                    && cached.farMesh != null && cached.farMeshRevision == revision) {
                return cached.farMesh;
            }
            if (cached.lodTier == ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.MID
                    && cached.midMesh != null && cached.midMeshRevision == revision) {
                return cached.midMesh;
            }
            return cached.mesh;
        }

        @Override
        public int renderFlagsFor(BlockPos position) {
            // An active crack is translucent and must never render in the opaque pass.
            if (hasCracks(position.immutable())) {
                return MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
            }
            // Any fluid forces the translucent pass even for otherwise opaque volumes.
            if (MicrovoxelClientVolumeStore.CLIENT_FLUIDS.containsKey(position.immutable())) {
                return MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
            }
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
            return cached == null
                    ? MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS
                    : cached.renderFlags;
        }

        @Override
        public int revisionOf(BlockPos position) {
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
            return cached == null ? Integer.MIN_VALUE : cached.volume.revision();
        }

        /** Fluid revision for the geometry key; unknown volumes report the minimum. */
        @Override
        public int fluidRevisionOf(BlockPos position) {
            MicrovoxelFluidView view = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(position.immutable());
            return view == null ? Integer.MIN_VALUE : view.revision();
        }

        /** Crack revision for the geometry key; 0 when the volume is not being mined. */
        @Override
        public int crackRevisionOf(BlockPos position) {
            return MicrovoxelClientState.crackRevisionOf(position.immutable());
        }
    }

    /**
     * Derives the GPU render pass for one volume. Every used material must render solid for
     * the opaque fast path; a single translucent material (glass, leaves, water) keeps the
     * combined translucent+animated flags. Conservative by construction: unknown or unparsable
     * materials fall back to the previous always-translucent behavior.
     */
    static int computeRenderFlags(MicrovoxelVolume volume) {
        try {
            for (int index = 1; index < volume.palette().size(); index++) {
                net.minecraft.world.level.block.state.BlockState state =
                        MicrovoxelSectionModel.parseBlockState(volume.palette().get(index));
                if (!state.isSolidRender()) {
                    return MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
                }
            }
            return MicrovoxelSectionModel.OPAQUE_MATERIAL_FLAGS;
        } catch (RuntimeException fallback) {
            return MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
        }
    }

    /** Positions whose light seal flipped and need an explicit engine recheck. */
    private static final java.util.Set<BlockPos> PENDING_LIGHT_CHECKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Optimistic fluid-fill preview: position and tick of a locally shown waterlogged flag
     * that the server has not confirmed yet. Confirmed by the authoritative block update,
     * reverted after {@link #FLUID_PREDICT_TIMEOUT_TICKS} ticks (denied fills flash water
     * for at most one second instead of desyncing forever).
     */
    private static final int FLUID_PREDICT_TIMEOUT_TICKS = 20;
    private static BlockPos predictedFluidAt;
    private static int predictedFluidTick;

    /**
     * Shows water instantly on water-bucket use against a known basin. Never cancels the
     * interaction: the server owns the fill, this only stops waiting a round trip to draw
     * it. Denied fills (no permission, no basin, nether) revert automatically.
     */
    public static void predictFluidUse(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;
        net.minecraft.world.item.ItemStack held = minecraft.player.getMainHandItem();
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) return;
        BlockPos position = hit.getBlockPos().immutable();
        if (held.is(net.minecraft.world.item.Items.LAVA_BUCKET)) {
            predictLavaFill(minecraft, position);
            return;
        }
        if (!held.is(net.minecraft.world.item.Items.WATER_BUCKET)) return;
        abandonFluidPrediction(minecraft);
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position);
        if (cached == null) return;
        net.minecraft.world.level.block.state.BlockState current =
                minecraft.level.getBlockState(position);
        if (!MicrovoxelBlocks.isMarker(current) || current.getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState preview = MicrovoxelBlocks.markerState(
                lightLevel(cached.volume, true), soundProfile(cached.volume), true);
        minecraft.level.setBlock(position, preview, 3);
        predictedFluidAt = position;
        predictedFluidTick = clientTick;
        MicrovoxelClientMetrics.inc("predict.fluid");
    }

    /**
     * Provisional lava data for instant lava-fill previews. Lava has no blockstate flag to
     * flip (it would read as water), so the preview installs full levels straight into the
     * fluid map at a losing revision: the authoritative packet overwrites it on arrival,
     * the timeout drops it on denial. Shares the single prediction slot with water.
     */
    private static boolean predictedFluidLava;

    /**
     * Single-slot discipline: a second prediction in the same window first settles the
     * previous one synchronously, so a water preview can never leak past a lava click.
     */
    private static void abandonFluidPrediction(Minecraft minecraft) {
        if (predictedFluidAt != null) {
            if (predictedFluidLava) {
                MicrovoxelClientVolumeStore.CLIENT_FLUIDS.remove(predictedFluidAt);
                MicrovoxelClientMeshScheduler.onFluidChanged(predictedFluidAt);
            } else {
                MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(predictedFluidAt);
                if (cached != null && minecraft.level != null) {
                    minecraft.level.setBlock(predictedFluidAt, MicrovoxelBlocks.markerState(
                            lightLevel(cached.volume), soundProfile(cached.volume), false), 3);
                }
            }
            predictedFluidAt = null;
        }
        // Always reset the mode flag: otherwise a later water preview is routed into the lava
        // reconciliation path and denied water fills are never reverted (phantom water).
        predictedFluidLava = false;
    }

    private static void predictLavaFill(Minecraft minecraft, BlockPos position) {
        abandonFluidPrediction(minecraft);
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position);
        if (cached == null || MicrovoxelClientVolumeStore.CLIENT_FLUIDS.containsKey(position)) return;
        byte[] levels = new byte[MicrovoxelVolume.CELL_COUNT];
        boolean anyAir = false;
        for (int cell = 0; cell < levels.length; cell++) {
            if (!cached.volume.occupied(cell)) {
                levels[cell] = 16;
                anyAir = true;
            }
        }
        if (!anyAir) return;
        MicrovoxelClientVolumeStore.CLIENT_FLUIDS.put(position, new MicrovoxelFluidView(levels, -1, true));
        MicrovoxelClientMeshScheduler.onFluidChanged(position);
        predictedFluidAt = position;
        predictedFluidLava = true;
        predictedFluidTick = clientTick;
        MicrovoxelClientMetrics.inc("predict.fluid.lava");
    }

    /** Confirms or reverts the optimistic fluid preview (see {@link #predictFluidUse}). */
    private static void reconcileFluidPrediction(Minecraft minecraft) {
        if (predictedFluidAt == null || minecraft == null || minecraft.level == null) return;
        if (predictedFluidLava) {
            reconcileLavaPrediction(minecraft);
            return;
        }
        net.minecraft.world.level.block.state.BlockState current =
                minecraft.level.getBlockState(predictedFluidAt);
        if (MicrovoxelBlocks.isMarker(current) && current.getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
            // Server confirmed (or re-sent) the wet flag: prediction converged, stop tracking.
            predictedFluidAt = null;
            MicrovoxelClientMetrics.inc("predict.fluid.confirmed");
            return;
        }
        if (clientTick - predictedFluidTick >= FLUID_PREDICT_TIMEOUT_TICKS) {
            // Denied fill: take the preview back instead of showing phantom water.
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(predictedFluidAt);
            if (cached != null) {
                minecraft.level.setBlock(predictedFluidAt, MicrovoxelBlocks.markerState(
                        lightLevel(cached.volume), soundProfile(cached.volume), false), 3);
            }
            predictedFluidAt = null;
            MicrovoxelClientMetrics.inc("predict.fluid.reverted");
        }
    }

    /**
     * Settles provisional lava data: confirmed once the server overwrites our losing
     * revision, reverted (data dropped, mesh rebuilt) on timeout.
     */
    private static void reconcileLavaPrediction(Minecraft minecraft) {
        MicrovoxelFluidView view = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(predictedFluidAt);
        if (view == null) {
            // Data vanished underneath (chunk clear, authoritative remove): nothing to settle.
            predictedFluidAt = null;
            return;
        }
        if (view.revision() != -1) {
            predictedFluidAt = null;
            MicrovoxelClientMetrics.inc("predict.fluid.confirmed");
            return;
        }
        if (clientTick - predictedFluidTick >= FLUID_PREDICT_TIMEOUT_TICKS) {
            MicrovoxelClientVolumeStore.CLIENT_FLUIDS.remove(predictedFluidAt, view);
            MicrovoxelClientMeshScheduler.onFluidChanged(predictedFluidAt);
            predictedFluidAt = null;
            MicrovoxelClientMetrics.inc("predict.fluid.reverted");
        }
    }

    static void replaceDisplayed(BlockPos position, MicrovoxelVolume volume) {
        MicrovoxelCachedVolume oldCached = MicrovoxelClientVolumeStore.VOLUMES.get(position);
        MicrovoxelVolume oldVolume = oldCached == null ? null : oldCached.volume;
        // Seal status before the swap; missing volumes count as unsealed.
        boolean wasSealed = oldCached != null && oldCached.lightSealed;
        if (volume == null) {
            PENDING_PLACEMENTS.remove(position);
            if (oldCached == null) return;
            MicrovoxelClientVolumeStore.VOLUMES.remove(position);
            removeFromChunk(position);
            MicrovoxelClientMeshScheduler.onDisplayedRemoved(position);
            // Demolishing a sealed wall must relight even though no blockstate changes here:
            // the removal is mesh-side until the server confirms.
            if (wasSealed) PENDING_LIGHT_CHECKS.add(position.immutable());
            return;
        }
        boolean nowSealed = volume.isLightSealed(MicrovoxelClientState::isOpaqueMaterial);
        if (oldCached == null) {
            addToChunk(position);
            MicrovoxelCachedVolume created = new MicrovoxelCachedVolume(position, volume, null);
            created.renderFlags = computeRenderFlags(volume);
            created.lightSealed = nowSealed;
            MicrovoxelClientVolumeStore.VOLUMES.put(position, created);
        } else {
            // Keep one cache object for the lifetime of a placed volume. Replacing it on every
            // predicted click used to spawn an unbounded chain of shape jobs and made every
            // terrain mesh obsolete before it could ever become visible.
            oldCached.updateVolume(volume);
            oldCached.renderFlags = computeRenderFlags(volume);
            oldCached.lightSealed = nowSealed;
            oldCached.midMesh = null;
            oldCached.farMesh = null;
        }
        // A seal flip with an identical marker blockstate never triggers a vanilla relight
        // (and the volume/block packet order can race), so queue an explicit engine recheck.
        // Unchanged seals cost one boolean compare and nothing else.
        if (wasSealed != nowSealed) PENDING_LIGHT_CHECKS.add(position.immutable());
        MicrovoxelClientMeshScheduler.onDisplayedChanged(position,
                oldVolume == null || MicrovoxelClientMeshScheduler.changesTouchBoundary(oldVolume, volume));
    }

    static void requestFullResync(String reason) {
        if (clientTick - lastResyncRequestTick < 20
                || !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE)) {
            return;
        }
        lastResyncRequestTick = clientTick;
        EclipseClientMod.LOGGER.warn("[MICROVOXEL] Requesting authoritative resync: {}", reason);
        snapshotConfirmed = false;
        nextReadyTick = clientTick + READY_RETRY_TICKS;
        sendControlAction(ACTION_HELLO, nextTransactionId++,
                MicrovoxelWire.MAJOR, MicrovoxelWire.MINOR, advertisedCapabilities());
        sendControlAction(ACTION_READY, nextTransactionId++, 0, 0, 0);
    }

    static void requestVolumeResync(BlockPos position, String reason) {
        MicrovoxelClientMetrics.inc("resync.volume.requested");
        if (!canRequestResync()) return;
        lastResyncRequestTick = clientTick;
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position);
        int revision = cached == null ? 0 : cached.volume.revision();
        EclipseClientMod.LOGGER.warn("[MICROVOXEL] Requesting volume resync at {}: {}",
                position.toShortString(), reason);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new MicrovoxelActionPayload(
                PROTOCOL_VERSION, MicrovoxelSessionToken.NONE, nextTransactionId++,
                ACTION_RESYNC_VOLUME, position.getX(), position.getY(), position.getZ(),
                0, revision, 0, 0, 0, 0, 0, 0));
    }

    private static void requestChunkResync(int chunkX, int chunkZ, String reason) {
        MicrovoxelClientMetrics.inc("resync.chunk.requested");
        if (!canRequestResync()) return;
        lastResyncRequestTick = clientTick;
        EclipseClientMod.LOGGER.warn("[MICROVOXEL] Requesting chunk resync {},{}: {}",
                chunkX, chunkZ, reason);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new MicrovoxelActionPayload(
                PROTOCOL_VERSION, MicrovoxelSessionToken.NONE, nextTransactionId++,
                ACTION_RESYNC_CHUNK, chunkX, 0, chunkZ,
                0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static boolean canRequestResync() {
        return clientTick - lastResyncRequestTick >= 20
                && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE);
    }

    static void sendControlAction(int action, long transactionId, int x, int y, int z) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE)) {
            return;
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new MicrovoxelActionPayload(
                        PROTOCOL_VERSION, MicrovoxelSessionToken.NONE, transactionId, action, x, y, z,
                        0, 0, 0, 0, 0, 0, 0, 0));
    }

    public static void noteMissingVolume(BlockPos position) {
        MicrovoxelClientMarkers.note(position, clientTick);
    }

    /**
     * Runs queued explicit light rechecks, trickled so a snapshot storm cannot stall the tick.
     * Only loaded positions are checked; the rest stay queued until their chunk exists.
     */
    private static void drainLightChecks(Minecraft minecraft) {
        if (PENDING_LIGHT_CHECKS.isEmpty() || minecraft == null || minecraft.level == null) return;
        int budget = 16;
        var iterator = PENDING_LIGHT_CHECKS.iterator();
        while (budget-- > 0 && iterator.hasNext()) {
            BlockPos position = iterator.next();
            iterator.remove();
            minecraft.level.getLightEngine().checkBlock(position);
            MicrovoxelClientMetrics.inc("light.checks");
        }
    }

    private static void processMissingMarkers() {
        MicrovoxelClientMarkers.process(activeLevel, clientTick, canRequestResync(),
                new MicrovoxelClientMarkers.ResyncSink() {
                    @Override
                    public void chunkResync(int chunkX, int chunkZ, String reason) {
                        requestChunkResync(chunkX, chunkZ, reason);
                    }

                    @Override
                    public void volumeResync(BlockPos position, String reason) {
                        requestVolumeResync(position, reason);
                    }
                });
    }

    public static MicrovoxelCachedVolume get(BlockPos position) {
        return MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
    }

    /**
     * Exact cell material under the crosshair for predicted hit feedback: mixed
     * sculptures shed chips of the cell actually struck, not of the whole block.
     * Null when the crosshair is elsewhere, the cell is empty or unparsable.
     */
    public static net.minecraft.world.level.block.state.BlockState hitCellState(BlockPos position) {
        if (position == null) return null;
        try {
            ua.rp.chat.microvoxel.MicrovoxelRaycaster.Hit hit =
                    ua.rp.chat.client.microvoxel.MicrovoxelInteractionController.currentHit();
            if (hit == null || hit.entry() == null) return null;
            if (hit.entry().x() != position.getX()
                    || hit.entry().y() != position.getY()
                    || hit.entry().z() != position.getZ()) return null;
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
            if (cached == null || cached.volume == null) return null;
            int cell = hit.cell();
            if (cell < 0 || cell >= ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT
                    || !cached.volume.occupied(cell)) return null;
            return MicrovoxelCachedVolume.parseBlockState(cached.volume.material(cell));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Parent blockstate for predicted break feedback: the dominant material of the
     * cached volume, or null for plain blocks and unknown volumes. Vanilla resolves
     * hit/break particles and sounds from the blockstate at the position (the marker
     * for every material), so effect hooks substitute this instead.
     */
    public static net.minecraft.world.level.block.state.BlockState parentState(BlockPos position) {
        if (position == null) return null;
        MicrovoxelCachedVolume cached;
        try {
            cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (cached == null || cached.volume == null) return null;
        String dominant;
        try {
            dominant = ua.rp.chat.microvoxel.MicrovoxelVolume.dominantMaterial(cached.volume);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (dominant == null) return null;
        try {
            return MicrovoxelCachedVolume.parseBlockState(dominant);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Installs the exact portable shape in client prediction before the use-item packet leaves.
     * The authoritative server path remains unchanged and either confirms or replaces this state.
     */
    public static boolean predictPortablePlacement(BlockPos position,
                                                   MicrovoxelItemData.Parsed parsed) {
        if (activeLevel == null || parsed == null
                || parsed.kind() != MicrovoxelItemData.Kind.CARVED
                || !activeLevel.getBlockState(position).isAir()) {
            return false;
        }

        BlockPos immutable = position.immutable();
        MicrovoxelVolume volume = parsed.volume().copy();
        replaceDisplayed(immutable, volume);
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
        if (cached != null) {
            // Placement is infrequent. This bounded synchronous mesh makes the first section
            // compilation exact; the normal queued job subsequently adds neighbour culling.
            cached.mesh = MicrovoxelGreedyMesher.build(volume, volume::materialAt);
            cached.meshRevision = volume.revision();
            cached.renderFlags = computeRenderFlags(volume);
            MicrovoxelClientMeshScheduler.queueChunkBatch(immutable);
        }
        PENDING_PLACEMENTS.put(
                immutable, clientTick + PLACEMENT_PREDICTION_TIMEOUT_TICKS);
        activeLevel.setBlock(
                immutable, MicrovoxelBlocks.markerState(
                        lightLevel(volume), soundProfile(volume)), 11);
        return true;
    }

    /**
     * Re-asserts the native marker for a volume whose socket the Carver hid locally for its
     * design hologram. The terrain section model only emits microvoxel geometry for a marker
     * block, so a stranded air socket renders nothing. A re-entered carving keeps the very same
     * marker state, so the server never re-sends it after the work; this settles the socket to
     * the carving already synced on this client. Returns true when a marker was (re)installed.
     */
    public static boolean refreshMarker(BlockPos position) {
        if (activeLevel == null || position == null) return false;
        BlockPos immutable = position.immutable();
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
        if (cached == null || cached.volume == null) return false;
        try {
            net.minecraft.world.level.block.state.BlockState desired = MicrovoxelBlocks.markerState(
                    lightLevel(cached.volume), soundProfile(cached.volume));
            if (!activeLevel.getBlockState(immutable).equals(desired)) {
                activeLevel.setBlock(immutable, desired, 11);
            }
            MicrovoxelClientMeshScheduler.queueRebuild(immutable);
            return true;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Client hook for a real (non-marker) block change. Occlusion is computed at mesh-build
     * time from the six direct neighbours, so a volume whose neighbour just appeared or
     * vanished keeps stale hidden or missing faces until something else rebuilds it. Re-mesh
     * exactly the axis neighbours of the change; {@link #queueRebuild} coalesces duplicates and
     * skips positions without a volume, so this is six cheap lookups per block update.
     */
    public static void onRealBlockChanged(BlockPos changed) {
        if (changed == null || activeLevel == null) return;
        BlockPos immutable = changed.immutable();
        if (MicrovoxelBlocks.isMarker(activeLevel.getBlockState(immutable))) return;
        MicrovoxelClientMeshScheduler.queueRebuild(immutable.offset(1, 0, 0));
        MicrovoxelClientMeshScheduler.queueRebuild(immutable.offset(-1, 0, 0));
        MicrovoxelClientMeshScheduler.queueRebuild(immutable.offset(0, 1, 0));
        MicrovoxelClientMeshScheduler.queueRebuild(immutable.offset(0, -1, 0));
        MicrovoxelClientMeshScheduler.queueRebuild(immutable.offset(0, 0, 1));
        MicrovoxelClientMeshScheduler.queueRebuild(immutable.offset(0, 0, -1));
    }

    /** True when the per-cell crack overlay feature flag is on. */
    public static boolean crackOverlayEnabled() {
        return MicrovoxelClientCracks.overlayEnabled();
    }

    /** Terrain-worker view of the active cracks on a volume, as {@code {cell, stage}} pairs. */
    public static java.util.List<int[]> cracksAt(BlockPos position) {
        return MicrovoxelClientCracks.cellsAt(position);
    }

    /** True when a volume shows at least one crack (forces the translucent render pass). */
    public static boolean hasCracks(BlockPos position) {
        return MicrovoxelClientCracks.hasCracks(position);
    }

    /** Monotonic per-volume crack revision for the section geometry key (0 when none). */
    public static int crackRevisionOf(BlockPos position) {
        return MicrovoxelClientCracks.revisionOf(position);
    }

    /** Applies one MINE_STAGE frame and queues a rebuild on a real transition. */
    static void applyMineStage(BlockPos position, int cell, int stage) {
        if (MicrovoxelClientCracks.apply(position, cell, stage)) {
            MicrovoxelClientMeshScheduler.onCracksChanged(position);
        }
    }

    /** Drops cracks whose cells no longer exist in an authoritative volume (e.g. after a break). */
    private static void pruneCracks(BlockPos position, MicrovoxelVolume authoritative) {
        if (MicrovoxelClientCracks.prune(position, authoritative)) {
            MicrovoxelClientMeshScheduler.onCracksChanged(position);
        }
    }

    private static void clearCracks(BlockPos position) {
        if (MicrovoxelClientCracks.clear(position)) {
            MicrovoxelClientMeshScheduler.onCracksChanged(position);
        }
    }

    /**
     * Predicted block-light level for locally placed volumes. Uses the exact server formula
     * (fractional emission over exposed cells), so predictions glow precisely as the server
     * will confirm instead of flashing full brightness until the authoritative packet lands.
     */
    private static int lightLevel(MicrovoxelVolume volume) {
        return lightLevel(volume, false);
    }

    /**
     * Water-aware variant for fluid-fill previews: dowsed wicks burn out, exactly like the
     * server computes them, so a predicted underwater torch never flashes at 14.
     */
    private static int lightLevel(MicrovoxelVolume volume, boolean waterlogged) {
        return volume.emissionLevel(material -> {
            if (waterlogged && isDowsedMaterial(material)) return 0;
            try {
                return MicrovoxelCachedVolume.parseBlockState(material).getLightEmission();
            } catch (RuntimeException unparsable) {
                return 0;
            }
        });
    }

    /**
     * Open-flame materials drown underwater (client mirror of the server rule): torches and
     * candles go dark, lanterns and glowstone keep shining.
     */
    static boolean isDowsedMaterial(String material) {
        String id = material.toLowerCase(java.util.Locale.ROOT);
        int properties = id.indexOf('[');
        String name = properties < 0 ? id : id.substring(0, properties);
        return name.contains("torch") || name.contains("candle");
    }

    private static int soundProfile(MicrovoxelVolume volume) {
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (volume.occupied(cell)) {
                return MicrovoxelBlocks.soundProfile(
                        MicrovoxelCachedVolume.parseBlockState(volume.material(cell)));
            }
        }
        return 0;
    }

    private static void processPendingPlacements() {
        if (activeLevel == null || PENDING_PLACEMENTS.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Integer>> iterator =
                PENDING_PLACEMENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            BlockPos position = entry.getKey();
            boolean marker = MicrovoxelBlocks.isMarker(activeLevel.getBlockState(position));
            boolean timedOut = entry.getValue() <= clientTick;
            if (marker && !timedOut) continue;

            iterator.remove();
            replaceDisplayed(position, null);
            if (marker) {
                activeLevel.setBlock(position,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 11);
            }
            if (timedOut) {
                requestVolumeResync(position,
                        "portable placement prediction timed out");
            }
        }
    }

    public static long nextTransactionId() {
        return nextTransactionId++;
    }

    /**
     * The per-login anti-replay token from {@code HELLO_ACK}, or {@link MicrovoxelSessionToken#NONE}
     * before the handshake completes. Any replay-sensitive action (a mutation, undo or redo) must
     * present this value; sending it without a token is refused server-side.
     */
    public static long sessionNonce() {
        return sessionNonce;
    }

    /** True once a token is available, i.e. replay-sensitive actions may be sent. */
    public static boolean hasSessionToken() {
        return sessionNonce != MicrovoxelSessionToken.NONE;
    }

    public static boolean predictRemove(BlockPos position, int cell, long transactionId) {
        return MicrovoxelClientPrediction.predictRemove(position, cell, transactionId);
    }

    public static boolean predictAdd(BlockPos position, int cell, String material, long transactionId) {
        return MicrovoxelClientPrediction.predictAdd(position, cell, material, transactionId);
    }

    public static boolean predictBrush(long transactionId, List<BrushOp> targets) {
        return MicrovoxelClientPrediction.predictBrush(transactionId, targets);
    }
    public static VoxelShape collisionShape(BlockPos position) {
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
        return cached == null ? null : cached.getShape();
    }

    /**
     * Client mirror of the server light-state resolution: the first opaque parent material of
     * a fully sealed volume, {@code null} otherwise. Consumed by the client LightEngine mixin
     * so locally propagated light agrees with the server. Results are cached per revision;
     * glass and carved builds always resolve to transparent.
     */
    public static net.minecraft.world.level.block.state.BlockState resolveLightState(BlockPos position) {
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
        if (cached == null) return null;
        // Lava volumes never report a parent: the server keeps their glowing marker so sealed
        // forges shine, and the client must agree instead of going dark.
        MicrovoxelFluidView fluid = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(position.immutable());
        if (fluid != null && fluid.lava()) return null;
        int revision = cached.volume.revision();
        if (cached.lightParentRevision == revision) return cached.lightParent;
        net.minecraft.world.level.block.state.BlockState parent = null;
        if (cached.volume.isLightSealed(MicrovoxelClientState::isOpaqueMaterial)) {
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                if (!cached.volume.occupied(cell)) continue;
                String material = cached.volume.material(cell);
                if (isOpaqueMaterial(material)) {
                    parent = MicrovoxelCachedVolume.parseBlockState(material);
                    break;
                }
            }
        }
        cached.lightParent = parent;
        cached.lightParentRevision = revision;
        return parent;
    }

    private static boolean isOpaqueMaterial(String material) {
        try {
            return MicrovoxelCachedVolume.parseBlockState(material).isSolidRender();
        } catch (RuntimeException unparsable) {
            return false;
        }
    }

    public static void probeShape(String hook, BlockPos position, VoxelShape shape) {
        if (!DEBUG) return;
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
        if (cached == null) return;
        String key = "shape:" + hook + ':' + position.asLong() + ':' + cached.volume.revision();
        if (!PROBE_EMITTED.add(key)) return;
        List<MicrovoxelVolume.Cuboid> cuboids = cached.volume.collisionCuboids();
        EclipseClientMod.LOGGER.info("[MICROVOXEL-PROBE] SHAPE hook={} pos={} revision={} cuboids={} aabbs={} bounds={}",
                hook, position.toShortString(), cached.volume.revision(), cuboids.size(), shape.toAabbs().size(),
                shape.isEmpty() ? "empty" : shape.bounds());
    }

    public static void probeRender(BlockPos position, MicrovoxelGreedyMesher.Face face,
                                   String material, int layerCount, String renderType, boolean translucent,
                                   float minU, float maxU, float minV, float maxV) {
        if (!DEBUG) return;
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
        if (cached == null) return;
        String key = "render:" + position.asLong() + ':' + cached.volume.revision();
        if (!PROBE_EMITTED.add(key)) return;
        EclipseClientMod.LOGGER.info(
                "[MICROVOXEL-PROBE] RENDER pos={} revision={} material={} face={} layers={} type={} translucent={} uv=[{},{}]x[{},{}]",
                position.toShortString(), cached.volume.revision(), material, face.direction(), layerCount,
                renderType, translucent, minU, maxU, minV, maxV);
    }

    public static Collection<Map.Entry<BlockPos, MicrovoxelCachedVolume>> volumesNear(
            double blockX, double blockZ, double radius) {
        return MicrovoxelClientMeshScheduler.volumesNear(blockX, blockZ, radius);
    }

    /** Returns immutable chunk-level render batches overlapping the requested horizontal radius. */
    public static Collection<ChunkBatch> batchesNear(double blockX, double blockZ, double radius) {
        return MicrovoxelClientMeshScheduler.batchesNear(blockX, blockZ, radius);
    }
    public static List<MicrovoxelRaycaster.Entry> raycastEntries(double x, double y, double z, double radius) {
        double radiusSquared = radius * radius;
        List<MicrovoxelRaycaster.Entry> result = new ArrayList<>();
        for (Map.Entry<BlockPos, MicrovoxelCachedVolume> entry : volumesNear(x, z, radius)) {
            BlockPos position = entry.getKey();
            double dx = position.getX() + 0.5 - x;
            double dy = position.getY() + 0.5 - y;
            double dz = position.getZ() + 0.5 - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                result.add(new MicrovoxelRaycaster.Entry(
                        position.getX(), position.getY(), position.getZ(), entry.getValue().volume));
            }
        }
        return result;
    }

    static void rebuild(BlockPos position) {
        MicrovoxelClientMeshScheduler.rebuild(position);
    }

    /** Used by the renderer's vertex AO sampler. Coordinates are local to {@code base}. */
    public static boolean solidAt(BlockPos base, int x, int y, int z) {
        return subCellSolid(base, x, y, z);
    }

    /**
     * Neighbour probe for shape-face culling: true when the micro sub-cell at a local coordinate
     * (possibly inside an adjacent volume or a real block) is solid. Mirrors the greedy mesher's
     * neighbour lookup so a shape's flush boundary face is culled instead of z-fighting the
     * neighbouring surface.
     */
    public static boolean shapeNeighborSolid(BlockPos base, int subX, int subY, int subZ) {
        return subCellSolid(base, subX, subY, subZ);
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Solid test of one micro sub-cell, honouring the owning volume's geometry: an occupied but
     * shaped cell is solid only where its shape actually occupies, so a neighbour's flush face is
     * not culled by empty space inside a partial shape (AO and culling both rely on this).
     */
    private static boolean subCellSolid(BlockPos base, int x, int y, int z) {
        int offsetX = Math.floorDiv(x, 16);
        int offsetY = Math.floorDiv(y, 16);
        int offsetZ = Math.floorDiv(z, 16);
        int localX = Math.floorMod(x, 16);
        int localY = Math.floorMod(y, 16);
        int localZ = Math.floorMod(z, 16);
        if (offsetX == 0 && offsetY == 0 && offsetZ == 0) {
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(base);
            return cached != null && microSolid(cached.volume, localX, localY, localZ);
        }
        BlockPos.MutableBlockPos scratch = SCRATCH_POS.get();
        scratch.set(base.getX() + offsetX, base.getY() + offsetY, base.getZ() + offsetZ);
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(scratch);
        if (cached != null) return microSolid(cached.volume, localX, localY, localZ);
        if (activeLevel == null) return false;
        return activeLevel.getBlockState(scratch).isSolidRender();
    }

    private static boolean microSolid(MicrovoxelVolume volume, int x, int y, int z) {
        if (volume.materialAt(x, y, z) == 0) return false;
        int shapeId = volume.shapeAt(MicrovoxelVolume.index(x, y, z));
        if (shapeId == 0) return true;
        return MicrovoxelShape.byId(shapeId).occupied(x, y, z);
    }

    /** Carving work focus for throttled section rebuilds; delegates to the mesh scheduler. */
    public static void setWorkFocus(BlockPos focus) {
        MicrovoxelClientMeshScheduler.setWorkFocus(focus);
    }

    /** Moves held focus sections and coalesced mesh jobs back to the live queues. */
    public static void flushWorkFocus() {
        MicrovoxelClientMeshScheduler.flushWorkFocus();
    }
    static void clearVolumes() {
        MicrovoxelClientVolumeStore.VOLUMES.clear();
        MicrovoxelClientVolumeStore.CHUNKS.clear();
        MicrovoxelClientMeshScheduler.clear();
        MicrovoxelClientLod.clear();
        PROBE_EMITTED.clear();
        MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.clear();
        MicrovoxelClientPrediction.clear();
        PENDING_PLACEMENTS.clear();
        PENDING_LIGHT_CHECKS.clear();
        MicrovoxelClientVolumeStore.CLIENT_FLUIDS.clear();
        MicrovoxelClientCracks.clearAll();
        predictedFluidAt = null;
        predictedFluidLava = false;
        MicrovoxelClientMarkers.clear();
        MicrovoxelSectionModel.clearThreadCaches();
        MicrovoxelItemModel.clearCache();
    }

    public static void clearChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Set<BlockPos> positions = MicrovoxelClientVolumeStore.CHUNKS.remove(key);
        if (positions != null) {
            for (BlockPos pos : positions) {
                MicrovoxelClientVolumeStore.VOLUMES.remove(pos);
                MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.remove(pos);
                PENDING_PLACEMENTS.remove(pos);
                MicrovoxelClientMarkers.remove(pos);
                PENDING_LIGHT_CHECKS.remove(pos);
                MicrovoxelClientVolumeStore.CLIENT_FLUIDS.remove(pos);
                clearCracks(pos);
                MicrovoxelClientMeshScheduler.clearWorkFocusIf(pos);
                MicrovoxelClientMeshScheduler.queueRebuild(pos);
            }
            MicrovoxelClientPrediction.dropChunkPredictions(chunkX, chunkZ);
            MicrovoxelClientMeshScheduler.enqueueChunkBatch(key);
        }
    }

    private static void addToChunk(BlockPos position) {
        MicrovoxelClientVolumeStore.CHUNKS.computeIfAbsent(chunkKey(position.getX() >> 4, position.getZ() >> 4), ignored -> new HashSet<>())
                .add(position);
    }

    private static void removeFromChunk(BlockPos position) {
        long key = chunkKey(position.getX() >> 4, position.getZ() >> 4);
        Set<BlockPos> indexed = MicrovoxelClientVolumeStore.CHUNKS.get(key);
        if (indexed == null) return;
        indexed.remove(position);
        if (indexed.isEmpty()) MicrovoxelClientVolumeStore.CHUNKS.remove(key);
        MicrovoxelClientMeshScheduler.enqueueChunkBatch(key);
    }

    static int floorChunk(double blockCoordinate) {
        return ((int) Math.floor(blockCoordinate)) >> 4;
    }

    static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }


    private static BlockPos readPosition(DataInputStream input) throws IOException {
        return MicrovoxelClientCodec.readPosition(input);
    }

    private static MicrovoxelVolume readRawVolume(DataInputStream input) throws IOException {
        return MicrovoxelClientCodec.readRawVolume(input);
    }

    private static MicrovoxelGeometry readOptionalGeometry(DataInputStream input) throws IOException {
        return MicrovoxelClientCodec.readOptionalGeometry(input);
    }


    /**
     * Installs authoritative fluid levels: stale revisions drop silently, fresh ones replace
     * the view and rebuild the section (surface heights changed). Levels outside 0..16 or a
     * wrong cell count reject the whole packet, like volumes do.
     */
    static void acceptFluid(BlockPos position, int revision, boolean lava, byte[] levels) {
        if (levels.length != MicrovoxelVolume.CELL_COUNT) return;
        MicrovoxelFluidView current = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(position);
        if (current != null && !MicrovoxelRevision.isNewer(revision, current.revision())) return;
        MicrovoxelClientVolumeStore.CLIENT_FLUIDS.put(position, new MicrovoxelFluidView(levels, revision, lava));
        MicrovoxelClientMetrics.inc("fluid.received");
        MicrovoxelClientMeshScheduler.onFluidChanged(position);
    }

    static void dropFluid(BlockPos position) {
        if (MicrovoxelClientVolumeStore.CLIENT_FLUIDS.remove(position) != null) {
            MicrovoxelClientMeshScheduler.onFluidChanged(position);
        }
    }

    /** Raw authoritative levels for rendering and physics refinement, or null when dry. */
    public static MicrovoxelFluidView fluidAt(BlockPos position) {
        return MicrovoxelClientFluids.at(position);
    }

    public static boolean shouldIgnoreWater(net.minecraft.world.entity.Entity entity) {
        return MicrovoxelClientFluids.shouldIgnoreWater(entity);
    }

    public static double voxelLavaHeight(net.minecraft.world.entity.Entity entity) {
        return MicrovoxelClientFluids.voxelLavaHeight(entity);
    }

    public static double voxelLavaHeightAt(net.minecraft.world.entity.Entity entity,
                                           net.minecraft.world.phys.Vec3 exact) {
        return MicrovoxelClientFluids.voxelLavaHeightAt(entity, exact);
    }

    public static double voxelLavaHeightAt(net.minecraft.world.level.Level level,
                                           net.minecraft.world.phys.Vec3 exact) {
        return MicrovoxelClientFluids.voxelLavaHeightAt(level, exact);
    }

    public static boolean cellWet(net.minecraft.world.phys.Vec3 exact) {
        return MicrovoxelClientFluids.cellWet(exact);
    }

    public static int fluidLevelAt(BlockPos base, int x, int y, int z) {
        return MicrovoxelClientFluids.fluidLevelAt(base, x, y, z);
    }
    /** Mirror of the server RLE fluid codec. Public for unit tests; delegates to the codec. */
    public static byte[] decodeLevels(byte[] encoded) throws IOException {
        return MicrovoxelClientCodec.decodeLevels(encoded);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        return MicrovoxelClientCodec.readVarInt(in);
    }

    private static String readUtf8(DataInputStream input) throws IOException {
        return MicrovoxelClientCodec.readUtf8(input);
    }


    public static net.minecraft.world.level.block.state.BlockState getBaseBlockState(BlockPos pos) {
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(pos.immutable());
        if (cached == null) return null;
        return cached.getBaseBlockState();
    }

    /** Resolves the actual palette material immediately behind a rendered micro-surface. */
    public static net.minecraft.world.level.block.state.BlockState materialStateAtSurface(
            BlockPos pos,
            net.minecraft.world.phys.Vec3 worldPosition,
            net.minecraft.world.phys.Vec3 outwardNormal
    ) {
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(pos.immutable());
        if (cached == null) return null;
        net.minecraft.world.phys.Vec3 inside = worldPosition.subtract(
                outwardNormal.normalize().scale(0.002));
        int x = Math.max(0, Math.min(15,
                (int) Math.floor((inside.x - pos.getX()) * 16.0)));
        int y = Math.max(0, Math.min(15,
                (int) Math.floor((inside.y - pos.getY()) * 16.0)));
        int z = Math.max(0, Math.min(15,
                (int) Math.floor((inside.z - pos.getZ()) * 16.0)));
        int material = cached.volume.materialAt(x, y, z);
        if (material <= 0 || material >= cached.volume.palette().size()) return null;
        return MicrovoxelCachedVolume.parseBlockState(cached.volume.palette().get(material));
    }

    public static final class ChunkBatch {
        private final int chunkX;
        private final int chunkZ;
        private final List<ChunkFace> faces;

        ChunkBatch(int chunkX, int chunkZ, List<ChunkFace> faces) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.faces = List.copyOf(faces);
        }

        public List<ChunkFace> faces() {
            return faces;
        }

        double distanceSquaredTo(double blockX, double blockZ) {
            double dx = (chunkX << 4) + 8.0 - blockX;
            double dz = (chunkZ << 4) + 8.0 - blockZ;
            return dx * dx + dz * dz;
        }
    }

    public record ChunkFace(BlockPos position, MicrovoxelCachedVolume cached, MicrovoxelGreedyMesher.Face face) {
    }

    private record MeshJob(int generation, int revision, int stride) {
    }

    /**
     * One predicted brush cell write targeting any volume (brushes cross block boundaries).
     * An empty material means removal.
     */
    public record BrushOp(BlockPos position, int cell, String material) {
        public BrushOp {
            position = position.immutable();
            if (material == null) material = "";
        }
    }

}
