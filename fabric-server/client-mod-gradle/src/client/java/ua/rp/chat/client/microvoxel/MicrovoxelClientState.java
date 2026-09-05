package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.client.mixin.CubeVoxelShapeInvoker;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelPrediction;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

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
    private static final int PROTOCOL_MAGIC = 0x4D;
    /** Version 6 adds the fluid kind byte to FLUID_UPSERT (lava engine). */
    public static final int PROTOCOL_VERSION = 6;
    private static final int CLEAR = 1;
    private static final int UPSERT = 2;
    private static final int REMOVE = 3;
    private static final int MESSAGE = 4;
    private static final int REGISTER_MATERIAL = 5;
    private static final int BATCH_UPSERT = 6;
    private static final int CLEAR_CHUNK = 7;
    private static final int DELTA_UPSERT = 8;
    private static final int TRANSACTION = 9;
    private static final int EDIT_RESULT = 10;
    private static final int SNAPSHOT_BEGIN = 11;
    private static final int SNAPSHOT_END = 12;
    private static final int FLUID_UPSERT = 14;
    private static final int FLUID_REMOVE = 15;
    private static final int ACTION_READY = 5;
    private static final int ACTION_RESYNC_VOLUME = 6;
    private static final int ACTION_RESYNC_CHUNK = 7;
    private static final int ACTION_SNAPSHOT_ACK = 14;
    private static final int READY_RETRY_TICKS = 60;
    private static final int MISSING_MARKER_GRACE_TICKS = 10;
    private static final int MISSING_MARKER_TARGETED_RETRY_TICKS = 50;
    private static final long MESH_BUDGET_NANOS = 3_000_000L;
    private static final long CHUNK_BATCH_BUDGET_NANOS = 1_500_000L;
    /** Read by terrain compilation workers; authoritative writes remain on the client thread. */
    private static final Map<BlockPos, CachedVolume> VOLUMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, Set<BlockPos>> CHUNKS = new HashMap<>();
    private static final Set<BlockPos> MESH_QUEUE = new LinkedHashSet<>();
    private static final Map<Integer, String> CLIENT_DICTIONARY = new HashMap<>();
    private static final Map<BlockPos, MicrovoxelVolume> AUTHORITATIVE_VOLUMES = new HashMap<>();
    private static final java.util.LinkedHashMap<Long, PendingEdit> PENDING_EDITS =
            new java.util.LinkedHashMap<>();
    private static final Map<BlockPos, MeshJob> MESHING_JOBS = new HashMap<>();
    private static final Set<BlockPos> MESH_DIRTY_DURING_BUILD = new HashSet<>();
    private static final Map<BlockPos, Integer> PENDING_PLACEMENTS = new HashMap<>();
    private static final int PLACEMENT_PREDICTION_TIMEOUT_TICKS = 60;
    /**
     * Authoritative fluid levels per volume, mirrored from the server (never simulated
     * locally). Read by terrain compilation workers; writes stay on the client thread.
     * A missing entry means dry — or not yet arrived, in which case vanilla water (when
     * the marker is waterlogged) covers the race until the packet lands.
     */
    private static final Map<BlockPos, FluidView> CLIENT_FLUIDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Immutable authoritative fluid snapshot for one volume, water or lava. */
    public record FluidView(byte[] levels, int revision, boolean lava) {
        public FluidView {
            levels = levels.clone();
        }

        public int level(int cell) {
            return Byte.toUnsignedInt(levels[cell]);
        }
    }
    private static final java.util.concurrent.ExecutorService MESHING_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), r -> {
                Thread t = new Thread(r, "OasisMesher");
                t.setDaemon(true);
                return t;
            });
    /**
     * A block-state packet and the matching microvoxel sync packet can arrive in either order.
     * Rebuilding once immediately is normally enough; rebuilding the same local boundary two
     * client ticks later makes the result deterministic without polling every volume every tick.
     */
    private static final Map<BlockPos, Integer> DELAYED_BOUNDARY_REBUILDS = new HashMap<>();
    /**
     * Rendering never walks the global volume map.  A batch is a stable, flattened snapshot of
     * one chunk's greedy faces and is rebuilt only when a volume in that chunk changes.  This is
     * deliberately CPU-side for now: it gives us deterministic dirty-region work and keeps the
     * renderer ready for a later VBO backend without making edit latency depend on GPU uploads.
     */
    private static final Map<Long, ChunkBatch> CHUNK_BATCHES = new HashMap<>();
    private static final Set<Long> CHUNK_BATCH_QUEUE = new LinkedHashSet<>();
    private static final Set<Long> SECTION_REBUILD_QUEUE = new LinkedHashSet<>();
    private static final int MAX_SECTION_REBUILDS_PER_TICK = 8;
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
    private static final Map<BlockPos, MissingMarker> MISSING_MARKERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static ClientLevel activeLevel;
    private static int clientTick;
    private static int stateGeneration;
    private static boolean snapshotConfirmed;
    private static int nextReadyTick;
    private static long activeSnapshotId = -1L;
    private static int lastResyncRequestTick = Integer.MIN_VALUE / 2;
    private static long nextTransactionId = 1L;

    private MicrovoxelClientState() {
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
            nextReadyTick = clientTick;
        }
        if (activeLevel != null && !snapshotConfirmed && clientTick >= nextReadyTick
                && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE)) {
            sendControlAction(ACTION_READY, nextTransactionId++, 0, 0, 0);
            nextReadyTick = clientTick + READY_RETRY_TICKS;
        }
        processMissingMarkers();
        processDelayedBoundaryRebuilds();
        processPendingPlacements();
        expirePendingEdits();
        reconcileFluidPrediction(minecraft);
        drainLightChecks(minecraft);
        if (clientTick % LOD_REVIEW_INTERVAL_TICKS == 0) reviewLod();
        drainFarThrottledRebuilds();
        drainWorkFocusFailsafe();
        // One client metrics line per minute at 20 TPS, correlatable with the server line.
        // Queue depths ride along every time: sustained growth here means meshing or section
        // rebuilds are not keeping up and LOD/mesh budgets need retuning.
        if (activeLevel != null && clientTick % 1200 == 0) {
            EclipseClientMod.LOGGER.info(MicrovoxelClientMetrics.summarize()
                    + " meshQueue=" + MESH_QUEUE.size()
                    + " sectionQueue=" + SECTION_REBUILD_QUEUE.size()
                    + " volumes=" + VOLUMES.size()
                    + " pending=" + PENDING_EDITS.size());
            if (MESH_QUEUE.size() > 512 || SECTION_REBUILD_QUEUE.size() > 64) {
                EclipseClientMod.LOGGER.warn(
                        "[MICROVOXEL] Rebuild backlog is growing: meshes={}, sections={}. "
                                + "Editing throughput exceeds background budgets.",
                        MESH_QUEUE.size(), SECTION_REBUILD_QUEUE.size());
            }
        }
        rebuildQueuedMeshes();
        rebuildQueuedChunkBatches();
        rebuildQueuedSections(minecraft);
    }

    public static void handle(MicrovoxelSyncPayload payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload.data()))) {
            int magic = input.readUnsignedByte();
            if (magic != PROTOCOL_MAGIC) {
                throw new IOException("Unsupported legacy microvoxel packet");
            }
            int protocolVersion = readVarInt(input);
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IOException("Microvoxel protocol mismatch: client=" + PROTOCOL_VERSION
                        + ", server=" + protocolVersion);
            }
            int type = input.readUnsignedByte();
            if (type == SNAPSHOT_BEGIN) {
                long snapshotId = input.readLong();
                if (snapshotId <= 0L || input.available() != 0) {
                    throw new IOException("Invalid snapshot-begin payload");
                }
                clearVolumes();
                activeSnapshotId = snapshotId;
                snapshotConfirmed = false;
                return;
            }
            if (type == SNAPSHOT_END) {
                long snapshotId = input.readLong();
                if (input.available() != 0) {
                    throw new IOException("Trailing snapshot-end payload");
                }
                if (snapshotId != activeSnapshotId) {
                    requestFullResync("snapshot end does not match active snapshot");
                    return;
                }
                sendControlAction(ACTION_SNAPSHOT_ACK, snapshotId, 0, 0, 0);
                snapshotConfirmed = true;
                activeSnapshotId = -1L;
                return;
            }
            if (type == CLEAR) {
                clearVolumes();
                return;
            }
            if (type == REMOVE) {
                BlockPos position = readPosition(input);
                acceptAuthoritative(position, null);
                return;
            }
            if (type == FLUID_UPSERT) {
                BlockPos position = readPosition(input).immutable();
                int revision = readVarInt(input);
                int kindCode = input.readUnsignedByte();
                if (kindCode != 0 && kindCode != 1) throw new IOException("Unknown fluid kind");
                int encodedBytes = readVarInt(input);
                byte[] encoded = input.readNBytes(encodedBytes);
                if (encoded.length != encodedBytes) throw new EOFException("Truncated fluid levels");
                if (input.available() != 0) throw new IOException("Trailing fluid payload bytes");
                acceptFluid(position, revision, kindCode == 1, decodeLevels(encoded));
                return;
            }
            if (type == FLUID_REMOVE) {
                BlockPos position = readPosition(input);
                if (input.available() != 0) throw new IOException("Trailing fluid-remove bytes");
                dropFluid(position.immutable());
                return;
            }
            if (type == MESSAGE) {
                String message = readUtf8(input);
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.gui.setOverlayMessage(Component.literal(message), false);
                return;
            }
            if (type == REGISTER_MATERIAL) {
                int id = readVarInt(input);
                String material = readUtf8(input);
                CLIENT_DICTIONARY.put(id, material);
                return;
            }
            if (type == CLEAR_CHUNK) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                clearChunk(chunkX, chunkZ);
                return;
            }
            if (type == TRANSACTION) {
                long transactionId = input.readLong();
                int size = readVarInt(input);
                if (size < 1 || size > 4096) throw new IOException("Invalid transaction size");
                List<TransactionChange> changes = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    BlockPos position = readPosition(input).immutable();
                    MicrovoxelVolume volume = input.readBoolean() ? readRawVolume(input) : null;
                    changes.add(new TransactionChange(position, volume));
                }
                if (input.available() != 0) throw new IOException("Trailing transaction bytes");
                // Decode and validate the complete packet before exposing any part of the edit.
                for (TransactionChange change : changes) applyTransactionChange(change);
                if (DEBUG) {
                    EclipseClientMod.LOGGER.info("[MICROVOXEL] Applied transaction {} ({} volumes)",
                            transactionId, changes.size());
                }
                return;
            }
            if (type == EDIT_RESULT) {
                long transactionId = input.readLong();
                boolean accepted = input.readBoolean();
                BlockPos position = readPosition(input).immutable();
                MicrovoxelVolume volume = input.readBoolean() ? readRawVolume(input) : null;
                if (input.available() != 0) throw new IOException("Trailing edit-result bytes");
                reconcileEdit(transactionId, accepted, position, volume);
                return;
            }
            if (type == DELTA_UPSERT) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int posXZ = input.readUnsignedByte();
                int posY = input.readShort();
                int revision = readVarInt(input);
                int cellIndex = readVarInt(input);
                String material = readUtf8(input);

                int x = (chunkX << 4) | ((posXZ >> 4) & 15);
                int z = (chunkZ << 4) | (posXZ & 15);
                BlockPos position = new BlockPos(x, posY, z);
                BlockPos immutable = position.immutable();

                MicrovoxelVolume base = AUTHORITATIVE_VOLUMES.get(immutable);
                if (base == null) {
                    CachedVolume cached = VOLUMES.get(immutable);
                    base = cached == null ? null : cached.volume;
                }
                if (base == null) {
                    requestVolumeResync(immutable, "delta without base volume");
                    return;
                }
                if (!MicrovoxelRevision.isNewer(revision, base.revision())) return;
                if (!MicrovoxelRevision.isImmediateNext(revision, base.revision())) {
                    requestVolumeResync(immutable, "delta revision gap local="
                            + base.revision() + ", remote=" + revision);
                    return;
                }
                MicrovoxelVolume authoritative = base.copy();
                try {
                    authoritative.update(cellIndex, material);
                } catch (IllegalStateException paletteFull) {
                    requestVolumeResync(immutable, "delta needs new material but palette is full");
                    return;
                }
                authoritative.setRevision(revision);
                acceptAuthoritative(immutable, authoritative);
                return;
            }
            if (type == BATCH_UPSERT) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int size = readVarInt(input);
                for (int entryIdx = 0; entryIdx < size; entryIdx++) {
                    int posXZ = input.readUnsignedByte();
                    int posY = input.readShort();
                    int x = (chunkX << 4) | ((posXZ >> 4) & 15);
                    int z = (chunkZ << 4) | (posXZ & 15);
                    BlockPos position = new BlockPos(x, posY, z);

                    int revision = readVarInt(input);
                    int paletteSize = readVarInt(input);
                    List<String> palette = new ArrayList<>(paletteSize);
                    for (int index = 0; index < paletteSize; index++) palette.add(readUtf8(input));

                    byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
                    int encoding = input.readUnsignedByte();
                    if (encoding == 0) {
                        if (input.readNBytes(cells, 0, cells.length) != cells.length) throw new EOFException("Truncated cells");
                    } else if (encoding == 1) {
                        int runs = readVarInt(input);
                        int cursor = 0;
                        for (int run = 0; run < runs; run++) {
                            int length = readVarInt(input);
                            byte material = input.readByte();
                            if (length < 1 || cursor + length > cells.length) throw new IOException("Invalid RLE run");
                            java.util.Arrays.fill(cells, cursor, cursor + length, material);
                            cursor += length;
                        }
                        if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
                    } else {
                        throw new IOException("Unknown cell encoding");
                    }

                    MicrovoxelVolume volume = new MicrovoxelVolume(revision, palette, cells);
                    BlockPos immutable = position.immutable();
                    if (!isFreshAuthoritative(immutable, revision)) continue;
                    acceptAuthoritative(immutable, volume);
                }
                return;
            }
            if (type != UPSERT) return;
            long tStart = System.nanoTime();
            BlockPos position = readPosition(input);
            int revision = readVarInt(input);
            int paletteSize = readVarInt(input);
            if (paletteSize < 1 || paletteSize > 32) throw new IOException("Invalid palette size");
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) palette.add(readUtf8(input));
            byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
            int encoding = input.readUnsignedByte();
            long tDecodeStart = System.nanoTime();
            if (encoding == 0) {
                if (input.readNBytes(cells, 0, cells.length) != cells.length) throw new EOFException("Truncated cells");
            } else if (encoding == 1) {
                int runs = readVarInt(input);
                int cursor = 0;
                for (int run = 0; run < runs; run++) {
                    int length = readVarInt(input);
                    byte material = input.readByte();
                    if (length < 1 || cursor + length > cells.length) throw new IOException("Invalid RLE run");
                    java.util.Arrays.fill(cells, cursor, cursor + length, material);
                    cursor += length;
                }
                if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
            } else {
                throw new IOException("Unknown cell encoding");
            }
            long tDecodeEnd = System.nanoTime();
            if (input.available() != 0) throw new IOException("Trailing microvoxel payload bytes");
            MicrovoxelVolume volume = new MicrovoxelVolume(revision, palette, cells);
            BlockPos immutable = position.immutable();
            if (!isFreshAuthoritative(immutable, revision)) return;
            CachedVolume oldCached = VOLUMES.get(immutable);
            long tRebuildStart = System.nanoTime();
            acceptAuthoritative(immutable, volume);
            long tRebuildEnd = System.nanoTime();
            long tQueueStart = tRebuildEnd;
            long tQueueEnd = tRebuildEnd;
            
            long tTotal = System.nanoTime() - tStart;
            double decodeUs = (tDecodeEnd - tDecodeStart) / 1000.0;
            double rebuildUs = (tRebuildEnd - tRebuildStart) / 1000.0;
            double queueUs = (tQueueEnd - tQueueStart) / 1000.0;
            double totalUs = tTotal / 1000.0;
            
            if (DEBUG) {
                EclipseClientMod.LOGGER.info("[MICROVOXEL-PERF] SYNC_UPSERT pos={} | Total: {}us (Decode: {}us, Rebuild: {}us, Queue: {}us) | Faces: {} | BoundaryTouch: {}",
                        immutable.toShortString(),
                        String.format("%.2f", totalUs),
                        String.format("%.2f", decodeUs),
                        String.format("%.2f", rebuildUs),
                        String.format("%.2f", queueUs),
                        VOLUMES.get(immutable) == null ? 0 : VOLUMES.get(immutable).mesh.size(),
                        oldCached == null || changesTouchBoundary(oldCached.volume, volume)
                );
            }
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Rejected sync payload: " + error.getMessage());
        }
    }

    /**
     * Settles one transaction by id. The authoritative volume becomes the new replay base;
     * every still-pending operation (including other cells of the same brush stroke) is
     * replayed on top, so a reject rolls back exactly one transaction and never wipes later
     * clicks. Brush results settle every volume the stroke touched.
     */
    /** True while one transaction still awaits its authoritative edit result. */
    public static boolean isPending(long transactionId) {
        return PENDING_EDITS.containsKey(transactionId);
    }

    private static void reconcileEdit(long transactionId, boolean accepted,
                                      BlockPos position, MicrovoxelVolume authoritative) {
        if (!accepted) MicrovoxelClientMetrics.inc("predict.rollback");
        PendingEdit pending = PENDING_EDITS.remove(transactionId);
        BlockPos target = pending == null ? position.immutable() : pending.position();
        if (!target.equals(position)) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Edit result {} targeted {} instead of {}",
                    transactionId, position.toShortString(), target.toShortString());
        }
        if (!accepted && DEBUG) {
            EclipseClientMod.LOGGER.info("[MICROVOXEL] Rolling back rejected edit {}", transactionId);
        }
        if (authoritative == null) AUTHORITATIVE_VOLUMES.remove(target);
        else AUTHORITATIVE_VOLUMES.put(target, authoritative.copy());

        settlePredicted(target);
        if (pending != null && pending.brushTargets() != null) {
            java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();
            seen.add(target);
            for (BrushOp op : pending.brushTargets()) {
                if (seen.add(op.position())) settlePredicted(op.position());
            }
        }
    }

    /** Shows the replayed preview where predictions remain, else the authoritative state. */
    private static void settlePredicted(BlockPos position) {
        if (hasPendingAt(position)) {
            rebuildPredicted(position);
        } else {
            MicrovoxelVolume authoritative = AUTHORITATIVE_VOLUMES.remove(position);
            replaceDisplayed(position, authoritative);
        }
    }

    /**
     * Guards full-state packets (upsert/batch/transaction). Deltas already check revisions;
     * without this, a duplicated or reordered full volume would silently overwrite newer state
     * with no resync trigger.
     */
    private static boolean isFreshAuthoritative(BlockPos position, int revision) {
        MicrovoxelVolume base = AUTHORITATIVE_VOLUMES.get(position);
        if (base == null) {
            CachedVolume cached = VOLUMES.get(position);
            base = cached == null ? null : cached.volume;
        }
        if (base == null) return true;
        return MicrovoxelRevision.isNewer(revision, base.revision());
    }

    private static void acceptAuthoritative(BlockPos position, MicrovoxelVolume authoritative) {
        BlockPos immutable = position.immutable();
        if (authoritative != null) MISSING_MARKERS.remove(immutable);
        PENDING_PLACEMENTS.remove(immutable);
        if (hasPendingAt(immutable)) {
            if (authoritative == null) AUTHORITATIVE_VOLUMES.remove(immutable);
            else AUTHORITATIVE_VOLUMES.put(immutable, authoritative.copy());
            rebuildPredicted(immutable);
        } else {
            replaceDisplayed(immutable, authoritative);
        }
    }

    private static boolean hasPendingAt(BlockPos position) {
        for (PendingEdit pending : PENDING_EDITS.values()) {
            if (pending.covers(position)) return true;
        }
        return false;
    }

    /**
     * Replays every still-unacknowledged operation for one volume in transaction order.
     * Add, remove and brush predictions share this path, so a late ACK for an early click
     * never discards the preview of later clicks from the same rapid editing stream.
     */
    private static void rebuildPredicted(BlockPos position) {
        MicrovoxelVolume authoritative = AUTHORITATIVE_VOLUMES.get(position);
        List<MicrovoxelPrediction.PredictedOp> ops = new java.util.ArrayList<>();
        for (PendingEdit pending : PENDING_EDITS.values()) {
            pending.collectOps(position, ops);
        }
        MicrovoxelVolume predicted = MicrovoxelPrediction.replayEdits(authoritative, ops);
        replaceDisplayed(position, predicted);
    }

    /** Rebuilds every volume touched by one pending transaction (brushes span volumes). */
    private static void rebuildPredictedFor(PendingEdit pending) {
        rebuildPredicted(pending.position());
        if (pending.brushTargets() != null) {
            java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();
            seen.add(pending.position());
            for (BrushOp op : pending.brushTargets()) {
                if (seen.add(op.position())) rebuildPredicted(op.position());
            }
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
            CachedVolume cached = VOLUMES.get(position.immutable());
            if (cached == null) return List.of();
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
            // Any fluid forces the translucent pass even for otherwise opaque volumes.
            if (CLIENT_FLUIDS.containsKey(position.immutable())) {
                return MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
            }
            CachedVolume cached = VOLUMES.get(position.immutable());
            return cached == null
                    ? MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS
                    : cached.renderFlags;
        }

        @Override
        public int revisionOf(BlockPos position) {
            CachedVolume cached = VOLUMES.get(position.immutable());
            return cached == null ? Integer.MIN_VALUE : cached.volume.revision();
        }

        /** Fluid revision for the geometry key; unknown volumes report the minimum. */
        @Override
        public int fluidRevisionOf(BlockPos position) {
            FluidView view = CLIENT_FLUIDS.get(position.immutable());
            return view == null ? Integer.MIN_VALUE : view.revision();
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
        CachedVolume cached = VOLUMES.get(position);
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
        if (predictedFluidAt == null) return;
        if (predictedFluidLava) {
            CLIENT_FLUIDS.remove(predictedFluidAt);
            queueChunkBatch(predictedFluidAt);
            scheduleBoundaryRebuild(predictedFluidAt);
        } else {
            CachedVolume cached = VOLUMES.get(predictedFluidAt);
            if (cached != null && minecraft.level != null) {
                minecraft.level.setBlock(predictedFluidAt, MicrovoxelBlocks.markerState(
                        lightLevel(cached.volume), soundProfile(cached.volume), false), 3);
            }
        }
        predictedFluidAt = null;
    }

    private static void predictLavaFill(Minecraft minecraft, BlockPos position) {
        abandonFluidPrediction(minecraft);
        CachedVolume cached = VOLUMES.get(position);
        if (cached == null || CLIENT_FLUIDS.containsKey(position)) return;
        byte[] levels = new byte[MicrovoxelVolume.CELL_COUNT];
        boolean anyAir = false;
        for (int cell = 0; cell < levels.length; cell++) {
            if (!cached.volume.occupied(cell)) {
                levels[cell] = 16;
                anyAir = true;
            }
        }
        if (!anyAir) return;
        CLIENT_FLUIDS.put(position, new FluidView(levels, -1, true));
        queueChunkBatch(position);
        scheduleBoundaryRebuild(position);
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
            CachedVolume cached = VOLUMES.get(predictedFluidAt);
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
        FluidView view = CLIENT_FLUIDS.get(predictedFluidAt);
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
            CLIENT_FLUIDS.remove(predictedFluidAt, view);
            queueChunkBatch(predictedFluidAt);
            scheduleBoundaryRebuild(predictedFluidAt);
            predictedFluidAt = null;
            MicrovoxelClientMetrics.inc("predict.fluid.reverted");
        }
    }

    private static void replaceDisplayed(BlockPos position, MicrovoxelVolume volume) {
        CachedVolume oldCached = VOLUMES.get(position);
        MicrovoxelVolume oldVolume = oldCached == null ? null : oldCached.volume;
        // Seal status before the swap; missing volumes count as unsealed.
        boolean wasSealed = oldCached != null && oldCached.lightSealed;
        if (volume == null) {
            PENDING_PLACEMENTS.remove(position);
            if (oldCached == null) return;
            VOLUMES.remove(position);
            removeFromChunk(position);
            queueRebuild(position);
            queueChunkBatch(position);
            scheduleBoundaryRebuild(position);
            // Demolishing a sealed wall must relight even though no blockstate changes here:
            // the removal is mesh-side until the server confirms.
            if (wasSealed) PENDING_LIGHT_CHECKS.add(position.immutable());
            return;
        }
        boolean nowSealed = volume.isLightSealed(MicrovoxelClientState::isOpaqueMaterial);
        if (oldCached == null) {
            addToChunk(position);
            CachedVolume created = new CachedVolume(position, volume, null);
            created.renderFlags = computeRenderFlags(volume);
            created.lightSealed = nowSealed;
            VOLUMES.put(position, created);
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
        requestRebuild(position);
        if (oldVolume == null || changesTouchBoundary(oldVolume, volume)) {
            queueNeighborsRebuild(position);
            scheduleBoundaryRebuild(position);
        }
    }

    private static void requestFullResync(String reason) {
        if (clientTick - lastResyncRequestTick < 20
                || !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE)) {
            return;
        }
        lastResyncRequestTick = clientTick;
        EclipseClientMod.LOGGER.warn("[MICROVOXEL] Requesting authoritative resync: {}", reason);
        snapshotConfirmed = false;
        nextReadyTick = clientTick + READY_RETRY_TICKS;
        sendControlAction(ACTION_READY, nextTransactionId++, 0, 0, 0);
    }

    private static void requestVolumeResync(BlockPos position, String reason) {
        MicrovoxelClientMetrics.inc("resync.volume.requested");
        if (!canRequestResync()) return;
        lastResyncRequestTick = clientTick;
        CachedVolume cached = VOLUMES.get(position);
        int revision = cached == null ? 0 : cached.volume.revision();
        EclipseClientMod.LOGGER.warn("[MICROVOXEL] Requesting volume resync at {}: {}",
                position.toShortString(), reason);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new MicrovoxelActionPayload(
                PROTOCOL_VERSION, nextTransactionId++,
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
                PROTOCOL_VERSION, nextTransactionId++,
                ACTION_RESYNC_CHUNK, chunkX, 0, chunkZ,
                0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static boolean canRequestResync() {
        return clientTick - lastResyncRequestTick >= 20
                && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE);
    }

    private static void sendControlAction(int action, long transactionId, int x, int y, int z) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                MicrovoxelActionPayload.TYPE)) {
            return;
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new MicrovoxelActionPayload(
                        PROTOCOL_VERSION, transactionId, action, x, y, z,
                        0, 0, 0, 0, 0, 0, 0, 0));
    }

    /**
    /**
     * Fails pending predicted edits closed. A lost edit-result packet must not pin the
     * authoritative copy and predicted mesh forever; expired edits roll back to the last
     * authoritative state and request a targeted volume resync.
     */
    private static void expirePendingEdits() {
        if (PENDING_EDITS.isEmpty()) return;
        var expired = new java.util.ArrayList<Long>();
        for (var entry : PENDING_EDITS.entrySet()) {
            if (clientTick - entry.getValue().createdTick() >= PENDING_EDIT_TIMEOUT_TICKS) {
                expired.add(entry.getKey());
            }
        }
        for (Long txId : expired) {
            PendingEdit pending = PENDING_EDITS.remove(txId);
            if (pending == null) continue;
            settlePredicted(pending.position());
            if (pending.brushTargets() != null) {
                java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();
                seen.add(pending.position());
                for (BrushOp op : pending.brushTargets()) {
                    if (seen.add(op.position())) settlePredicted(op.position());
                }
            }
            requestVolumeResync(pending.position(), "pending edit timed out");
        }
    }

    public static void noteMissingVolume(BlockPos position) {
        BlockPos immutable = position.immutable();
        if (VOLUMES.containsKey(immutable)) return;
        if (MISSING_MARKERS.size() >= MAX_MISSING_MARKERS) return;
        MISSING_MARKERS.putIfAbsent(immutable,
                new MissingMarker(clientTick, clientTick + MISSING_MARKER_GRACE_TICKS, 0));
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
        if (activeLevel == null || MISSING_MARKERS.isEmpty()) return;
        int inspected = 0;
        for (Map.Entry<BlockPos, MissingMarker> entry : MISSING_MARKERS.entrySet()) {
            if (inspected++ >= 32) break;
            BlockPos position = entry.getKey();
            MissingMarker missing = entry.getValue();
            if (VOLUMES.containsKey(position)
                    || !MicrovoxelBlocks.isMarker(activeLevel.getBlockState(position))) {
                MISSING_MARKERS.remove(position, missing);
                continue;
            }
            if (clientTick - missing.firstSeenTick() > READY_RETRY_TICKS * 10
                    || missing.attempts() >= MISSING_MARKER_MAX_ATTEMPTS) {
                MISSING_MARKERS.remove(position, missing);
                continue;
            }
            if (clientTick < missing.nextAttemptTick() || !canRequestResync()) continue;
            if (missing.attempts() == 0) {
                requestChunkResync(position.getX() >> 4, position.getZ() >> 4,
                        "rendered marker has no volume");
                MISSING_MARKERS.replace(position, missing,
                        new MissingMarker(missing.firstSeenTick(),
                                clientTick + MISSING_MARKER_TARGETED_RETRY_TICKS, 1));
            } else {
                requestVolumeResync(position,
                        "marker still has no volume after chunk resync");
                MISSING_MARKERS.replace(position, missing,
                        new MissingMarker(missing.firstSeenTick(),
                                clientTick + READY_RETRY_TICKS, missing.attempts() + 1));
            }
        }
    }

    public static CachedVolume get(BlockPos position) {
        return VOLUMES.get(position.immutable());
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
            CachedVolume cached = VOLUMES.get(position.immutable());
            if (cached == null || cached.volume == null) return null;
            int cell = hit.cell();
            if (cell < 0 || cell >= ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT
                    || !cached.volume.occupied(cell)) return null;
            return CachedVolume.parseBlockState(cached.volume.material(cell));
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
        CachedVolume cached;
        try {
            cached = VOLUMES.get(position.immutable());
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
            return CachedVolume.parseBlockState(dominant);
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
        CachedVolume cached = VOLUMES.get(immutable);
        if (cached != null) {
            // Placement is infrequent. This bounded synchronous mesh makes the first section
            // compilation exact; the normal queued job subsequently adds neighbour culling.
            cached.mesh = MicrovoxelGreedyMesher.build(volume, volume::materialAt);
            cached.meshRevision = volume.revision();
            cached.renderFlags = computeRenderFlags(volume);
            queueChunkBatch(immutable);
        }
        PENDING_PLACEMENTS.put(
                immutable, clientTick + PLACEMENT_PREDICTION_TIMEOUT_TICKS);
        activeLevel.setBlock(
                immutable, MicrovoxelBlocks.markerState(
                        lightLevel(volume), soundProfile(volume)), 11);
        return true;
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
                return CachedVolume.parseBlockState(material).getLightEmission();
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
                        CachedVolume.parseBlockState(volume.material(cell)));
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

    /** Maximum unacknowledged predictions; older ones are rebased by authoritative state. */
    private static final int MAX_PENDING_EDITS = 32;

    /**
     * Applies a remove locally before the network round-trip. The authoritative base is retained
     * and every still-pending operation is replayed after each ACK/reject, so rollback never
     * discards later clicks from the same rapid editing stream.
     */
    public static boolean predictRemove(BlockPos position, int cell, long transactionId) {
        BlockPos immutable = position.immutable();
        CachedVolume cached = VOLUMES.get(immutable);
        if (cached == null || !cached.volume.occupied(cell)) return false;
        MicrovoxelClientMetrics.inc("predict.remove");
        if (!reservePendingSlot()) return false;
        AUTHORITATIVE_VOLUMES.putIfAbsent(immutable, cached.volume.copy());
        PENDING_EDITS.put(transactionId,
                new PendingEdit(transactionId, immutable, cell, "", null, clientTick));
        rebuildPredicted(immutable);
        return true;
    }

    /**
     * Applies a single-cell placement locally before the network round-trip. The material is a
     * preview hint derived from the held block; the authoritative server state (exact blockstate
     * string, palette compaction) replaces it as soon as the edit result arrives.
     */
    public static boolean predictAdd(BlockPos position, int cell, String material, long transactionId) {
        BlockPos immutable = position.immutable();
        if (material == null || material.isEmpty()) return false;
        CachedVolume cached = VOLUMES.get(immutable);
        if (cached != null && cached.volume.occupied(cell)) return false;
        MicrovoxelClientMetrics.inc("predict.add");
        if (!reservePendingSlot()) return false;
        if (cached != null) AUTHORITATIVE_VOLUMES.putIfAbsent(immutable, cached.volume.copy());
        PENDING_EDITS.put(transactionId,
                new PendingEdit(transactionId, immutable, cell, material, null, clientTick));
        rebuildPredicted(immutable);
        return true;
    }

    /**
     * Applies a whole brush stroke locally before the network round-trip. Every affected cell
     * across all touched volumes is predicted at once; each touched volume keeps its own
     * authoritative base so later ACKs replay the remaining stroke correctly.
     */
    public static boolean predictBrush(long transactionId, List<BrushOp> targets) {
        if (targets == null || targets.isEmpty()) return false;
        MicrovoxelClientMetrics.inc("predict.brush");
        if (!reservePendingSlot()) return false;
        java.util.HashSet<BlockPos> bases = new java.util.HashSet<>();
        for (BrushOp op : targets) {
            CachedVolume cached = VOLUMES.get(op.position());
            if (cached == null) continue;
            if (op.material().isEmpty() && !cached.volume.occupied(op.cell())) continue;
            if (!op.material().isEmpty() && cached.volume.occupied(op.cell())) continue;
            if (bases.add(op.position())) {
                AUTHORITATIVE_VOLUMES.putIfAbsent(op.position(), cached.volume.copy());
            }
        }
        if (bases.isEmpty()) return false;
        BlockPos anchor = targets.get(0).position();
        PENDING_EDITS.put(transactionId,
                new PendingEdit(transactionId, anchor, -1, "", List.copyOf(targets), clientTick));
        for (BlockPos base : bases) rebuildPredicted(base);
        return true;
    }

    /** Bounds the pending set so a stalled connection cannot accumulate unbounded previews. */
    private static boolean reservePendingSlot() {
        if (PENDING_EDITS.size() < MAX_PENDING_EDITS) return true;
        Long oldest = PENDING_EDITS.keySet().iterator().next();
        PendingEdit dropped = PENDING_EDITS.remove(oldest);
        if (dropped != null) {
            rebuildPredictedFor(dropped);
            requestVolumeResync(dropped.position(), "pending prediction budget exceeded");
        }
        return PENDING_EDITS.size() < MAX_PENDING_EDITS;
    }

    public static VoxelShape collisionShape(BlockPos position) {
        CachedVolume cached = VOLUMES.get(position.immutable());
        return cached == null ? null : cached.getShape();
    }

    /**
     * Client mirror of the server light-state resolution: the first opaque parent material of
     * a fully sealed volume, {@code null} otherwise. Consumed by the client LightEngine mixin
     * so locally propagated light agrees with the server. Results are cached per revision;
     * glass and carved builds always resolve to transparent.
     */
    public static net.minecraft.world.level.block.state.BlockState resolveLightState(BlockPos position) {
        CachedVolume cached = VOLUMES.get(position.immutable());
        if (cached == null) return null;
        // Lava volumes never report a parent: the server keeps their glowing marker so sealed
        // forges shine, and the client must agree instead of going dark.
        FluidView fluid = CLIENT_FLUIDS.get(position.immutable());
        if (fluid != null && fluid.lava()) return null;
        int revision = cached.volume.revision();
        if (cached.lightParentRevision == revision) return cached.lightParent;
        net.minecraft.world.level.block.state.BlockState parent = null;
        if (cached.volume.isLightSealed(MicrovoxelClientState::isOpaqueMaterial)) {
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                if (!cached.volume.occupied(cell)) continue;
                String material = cached.volume.material(cell);
                if (isOpaqueMaterial(material)) {
                    parent = CachedVolume.parseBlockState(material);
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
            return CachedVolume.parseBlockState(material).isSolidRender();
        } catch (RuntimeException unparsable) {
            return false;
        }
    }

    public static void probeShape(String hook, BlockPos position, VoxelShape shape) {
        if (!DEBUG) return;
        CachedVolume cached = VOLUMES.get(position.immutable());
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
        CachedVolume cached = VOLUMES.get(position.immutable());
        if (cached == null) return;
        String key = "render:" + position.asLong() + ':' + cached.volume.revision();
        if (!PROBE_EMITTED.add(key)) return;
        EclipseClientMod.LOGGER.info(
                "[MICROVOXEL-PROBE] RENDER pos={} revision={} material={} face={} layers={} type={} translucent={} uv=[{},{}]x[{},{}]",
                position.toShortString(), cached.volume.revision(), material, face.direction(), layerCount,
                renderType, translucent, minU, maxU, minV, maxV);
    }

    public static Collection<Map.Entry<BlockPos, CachedVolume>> volumesNear(
            double blockX, double blockZ, double radius) {
        int minChunkX = floorChunk(blockX - radius);
        int maxChunkX = floorChunk(blockX + radius);
        int minChunkZ = floorChunk(blockZ - radius);
        int maxChunkZ = floorChunk(blockZ + radius);
        List<Map.Entry<BlockPos, CachedVolume>> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (BlockPos position : CHUNKS.getOrDefault(chunkKey(chunkX, chunkZ), Set.of())) {
                    CachedVolume volume = VOLUMES.get(position);
                    if (volume != null) result.add(Map.entry(position, volume));
                }
            }
        }
        return result;
    }

    /** Returns immutable chunk-level render batches overlapping the requested horizontal radius. */
    public static Collection<ChunkBatch> batchesNear(double blockX, double blockZ, double radius) {
        int minChunkX = floorChunk(blockX - radius);
        int maxChunkX = floorChunk(blockX + radius);
        int minChunkZ = floorChunk(blockZ - radius);
        int maxChunkZ = floorChunk(blockZ + radius);
        List<ChunkBatch> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkBatch batch = CHUNK_BATCHES.get(chunkKey(chunkX, chunkZ));
                if (batch != null && !batch.faces.isEmpty()) result.add(batch);
            }
        }
        result.sort(java.util.Comparator.comparingDouble(batch -> batch.distanceSquaredTo(blockX, blockZ)));
        return result;
    }

    public static List<MicrovoxelRaycaster.Entry> raycastEntries(double x, double y, double z, double radius) {
        double radiusSquared = radius * radius;
        List<MicrovoxelRaycaster.Entry> result = new ArrayList<>();
        for (Map.Entry<BlockPos, CachedVolume> entry : volumesNear(x, z, radius)) {
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

    /**
     * Three-tier distance LOD (see {@link ua.rp.chat.microvoxel.MicrovoxelLodTiers}):
     * stride-1 inside 24 m, stride-2 out to 72 m, stride-4 silhouette past that, each
     * boundary with a 15% hysteresis dead band. Reviewed every 40 client ticks.
     */
    private static final int LOD_REVIEW_INTERVAL_TICKS = 40;
    /** Far-tier background builds per review: teleports converge over seconds, not one spike. */
    private static final int LOD_FAR_BUILDS_PER_REVIEW = 8;
    /** Crosshair hero radius squared: aimed-at volumes within 8 m always render full. */
    private static final double LOD_HERO_DISTANCE_SQUARED = 8.0 * 8.0;
    /** Far content edits batch to this cadence instead of rebuilding instantly. */
    private static final int LOD_FAR_THROTTLE_TICKS = 20;
    private static final java.util.Map<BlockPos, Long> FAR_THROTTLED_REBUILDS =
            new java.util.LinkedHashMap<>();

    private static double cameraDistanceSquared(BlockPos position) {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) return 0.0;
            net.minecraft.world.phys.Vec3 camera =
                    minecraft.gameRenderer.getMainCamera().position();
            double dx = position.getX() + 0.5 - camera.x;
            double dy = position.getY() + 0.5 - camera.y;
            double dz = position.getZ() + 0.5 - camera.z;
            return dx * dx + dy * dy + dz * dz;
        } catch (RuntimeException unavailable) {
            return 0.0;
        }
    }

    /**
     * Reconciles mesh resolution with camera distance across three tiers. Runs every 40
     * client ticks: a full volume-map scan at that rate is negligible, while per-frame
     * checks would not be. Only flips flags and requeues stale sides; MeshJob coalescing
     * dedups spam. Far-tier builds are budgeted per review so teleports converge over
     * seconds instead of one frame spike.
     */
    private static void reviewLod() {
        BlockPos hero = heroPosition();
        int near = 0;
        int mid = 0;
        int far = 0;
        int farBuilds = 0;
        for (CachedVolume cached : VOLUMES.values()) {
            double distanceSquared = cameraDistanceSquared(cached.position);
            ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier want =
                    ua.rp.chat.microvoxel.MicrovoxelLodTiers.wantTier(
                            distanceSquared, cached.lodTier);
            if (hero != null && hero.equals(cached.position)) {
                want = ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.NEAR;
            }
            if (want != cached.lodTier) {
                cached.lodTier = want;
                MicrovoxelClientMetrics.inc("lod.flips");
            }
            int revision = cached.volume.revision();
            boolean stale = switch (want) {
                case NEAR -> cached.meshRevision != revision;
                case MID -> cached.midMesh == null || cached.midMeshRevision != revision;
                case FAR -> cached.farMesh == null || cached.farMeshRevision != revision;
            };
            if (stale) {
                if (want == ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.FAR
                        && farBuilds >= LOD_FAR_BUILDS_PER_REVIEW) {
                    continue;
                }
                if (want == ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.FAR) farBuilds++;
                MESH_QUEUE.add(cached.position);
            }
            switch (want) {
                case NEAR -> near++;
                case MID -> mid++;
                case FAR -> far++;
            }
        }
        MicrovoxelClientMetrics.add("lod.volumes.near", near - MicrovoxelClientMetrics.get("lod.volumes.near"));
        MicrovoxelClientMetrics.add("lod.volumes.mid", mid - MicrovoxelClientMetrics.get("lod.volumes.mid"));
        MicrovoxelClientMetrics.add("lod.volumes.far", far - MicrovoxelClientMetrics.get("lod.volumes.far"));
        MicrovoxelClientMetrics.add("lod.farbuilds", farBuilds);
    }

    /**
     * Crosshair hero: the aimed-at volume inside 8 m always renders full detail, which
     * covers the carving focus block during design without a carver dependency.
     */
    private static BlockPos heroPosition() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) return null;
            if (!(minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) {
                return null;
            }
            BlockPos pos = hit.getBlockPos();
            if (cameraDistanceSquared(pos) > LOD_HERO_DISTANCE_SQUARED) return null;
            CachedVolume cached = VOLUMES.get(pos.immutable());
            return cached == null ? null : pos.immutable();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Far content edits batch to one rebuild per second instead of rebuilding instantly:
     * at 72 m+ nobody reads a single carved cell the tick it lands. Near and mid volumes
     * rebuild immediately; the queue drain moves throttled ones when due.
     */
    private static void requestRebuild(BlockPos position) {
        CachedVolume cached = VOLUMES.get(position.immutable());
        if (cached != null
                && cached.lodTier == ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.FAR) {
            FAR_THROTTLED_REBUILDS.put(position.immutable(), (long) clientTick + LOD_FAR_THROTTLE_TICKS);
            MicrovoxelClientMetrics.inc("lod.throttled");
            return;
        }
        rebuild(position);
    }

    private static void drainFarThrottledRebuilds() {
        if (FAR_THROTTLED_REBUILDS.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<BlockPos, Long>> iterator =
                FAR_THROTTLED_REBUILDS.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<BlockPos, Long> entry = iterator.next();
            if (entry.getValue() <= (long) clientTick) {
                iterator.remove();
                MESH_QUEUE.add(entry.getKey());
            }
        }
    }

    private static void rebuild(BlockPos position) {
        BlockPos immutablePos = position.immutable();
        CachedVolume center = VOLUMES.get(immutablePos);
        if (center == null) return;

        int stride = ua.rp.chat.microvoxel.MicrovoxelLodTiers.strideFor(center.lodTier);
        MeshJob job = new MeshJob(stateGeneration, center.volume.revision(), stride);
        if (MESHING_JOBS.putIfAbsent(immutablePos, job) != null) {
            MESH_DIRTY_DURING_BUILD.add(immutablePos);
            return;
        }

        CachedVolume downVol = VOLUMES.get(immutablePos.below());
        CachedVolume upVol = VOLUMES.get(immutablePos.above());
        CachedVolume northVol = VOLUMES.get(immutablePos.north());
        CachedVolume southVol = VOLUMES.get(immutablePos.south());
        CachedVolume westVol = VOLUMES.get(immutablePos.west());
        CachedVolume eastVol = VOLUMES.get(immutablePos.east());

        boolean downSolid = (downVol == null && activeLevel != null) && activeLevel.getBlockState(immutablePos.below()).isSolidRender();
        boolean upSolid = (upVol == null && activeLevel != null) && activeLevel.getBlockState(immutablePos.above()).isSolidRender();
        boolean northSolid = (northVol == null && activeLevel != null) && activeLevel.getBlockState(immutablePos.north()).isSolidRender();
        boolean southSolid = (southVol == null && activeLevel != null) && activeLevel.getBlockState(immutablePos.south()).isSolidRender();
        boolean westSolid = (westVol == null && activeLevel != null) && activeLevel.getBlockState(immutablePos.west()).isSolidRender();
        boolean eastSolid = (eastVol == null && activeLevel != null) && activeLevel.getBlockState(immutablePos.east()).isSolidRender();

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
                List<MicrovoxelGreedyMesher.Face> mesh =
                        MicrovoxelGreedyMesher.build(centerVol, (x, y, z) -> {
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
                }, jobStride);
                MicrovoxelClientMetrics.inc("mesh.jobs");
                MicrovoxelClientMetrics.add("mesh.us", (System.nanoTime() - meshStart) / 1000L);

                Minecraft.getInstance().execute(() -> {
                    if (!MESHING_JOBS.remove(immutablePos, job)) return;
                    CachedVolume currentCenter = job.generation == stateGeneration ? VOLUMES.get(immutablePos) : null;
                    boolean current = currentCenter != null && currentCenter.volume.revision() == job.revision;
                    if (currentCenter != null && job.generation == stateGeneration) {
                        // A continuously edited volume may already be one revision ahead. Showing
                        // this completed intermediate mesh is still strictly better than freezing
                        // the old geometry until the mouse button is released. Only one job per
                        // position exists, so intermediate results remain monotonically ordered.
                        // All three tiers cache side by side so LOD flips never trigger
                        // a fresh background build on their own.
                        if (job.stride == 1) {
                            currentCenter.mesh = mesh;
                            currentCenter.meshRevision = job.revision;
                        } else if (job.stride == 2) {
                            currentCenter.midMesh = mesh;
                            currentCenter.midMeshRevision = job.revision;
                        } else {
                            currentCenter.farMesh = mesh;
                            currentCenter.farMeshRevision = job.revision;
                        }
                        queueChunkBatch(immutablePos);
                    }
                    if (job.generation == stateGeneration
                            && (MESH_DIRTY_DURING_BUILD.remove(immutablePos) || !current)) {
                        requeueMesh(immutablePos);
                    }
                });
            } catch (Exception error) {
                EclipseClientMod.LOGGER.error("Failed background meshing for " + immutablePos, error);
                Minecraft.getInstance().execute(() -> {
                    if (!MESHING_JOBS.remove(immutablePos, job)) return;
                    if (job.generation == stateGeneration && MESH_DIRTY_DURING_BUILD.remove(immutablePos)) {
                        requeueMesh(immutablePos);
                    }
                });
            }
        });
    }

    /** Used by the renderer's vertex AO sampler. Coordinates are local to {@code base}. */
    public static boolean solidAt(BlockPos base, int x, int y, int z) {
        return materialAt(base, x, y, z) != 0;
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static int materialAt(BlockPos base, int x, int y, int z) {
        int offsetX = Math.floorDiv(x, 16);
        int offsetY = Math.floorDiv(y, 16);
        int offsetZ = Math.floorDiv(z, 16);
        if (offsetX == 0 && offsetY == 0 && offsetZ == 0) {
            CachedVolume cached = VOLUMES.get(base);
            return cached != null ? cached.volume.materialAt(x, y, z) : 0;
        }
        BlockPos.MutableBlockPos scratch = SCRATCH_POS.get();
        scratch.set(base.getX() + offsetX, base.getY() + offsetY, base.getZ() + offsetZ);
        CachedVolume cached = VOLUMES.get(scratch);
        return cached != null ? cached.volume.materialAt(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16)) : 0;
    }

    private static boolean changesTouchBoundary(MicrovoxelVolume oldVolume, MicrovoxelVolume newVolume) {
        if (oldVolume == null || newVolume == null) return true;
        // Zero-copy shell comparison; the old double-clone price is gone.
        return oldVolume.boundaryDiffersFrom(newVolume);
    }

    private static void queueRebuild(BlockPos position) {
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
        DELAYED_BOUNDARY_REBUILDS.put(position.immutable(), clientTick + 2);
    }

    private static void processDelayedBoundaryRebuilds() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = DELAYED_BOUNDARY_REBUILDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() > clientTick) continue;
            queueRebuild(entry.getKey());
            iterator.remove();
        }
    }

    private static void queueChunkBatch(BlockPos position) {
        if (Boolean.getBoolean("rpchat.microvoxel.legacyRenderer")) {
            CHUNK_BATCH_QUEUE.add(chunkKey(position.getX() >> 4, position.getZ() >> 4));
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

    /**
     * Carving work focus for throttled section rebuilds. While set, render sections
     * covering the focused volume are held back and flushed on work-phase crossings
     * instead of every applied slice. Clearing the focus always flushes first, so no
     * update can ever strand.
     */
    private static BlockPos workFocus;
    private static final java.util.Set<Long> HELD_WORK_SECTIONS = new java.util.LinkedHashSet<>();
    private static final java.util.Set<BlockPos> PENDING_WORK_MESH = new java.util.LinkedHashSet<>();
    private static int lastWorkFlushTick;

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
        lastWorkFlushTick = clientTick;
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
    private static void drainWorkFocusFailsafe() {
        if (workFocus == null) return;
        if ((!HELD_WORK_SECTIONS.isEmpty() || !PENDING_WORK_MESH.isEmpty())
                && clientTick - lastWorkFlushTick > 60) {
            flushWorkFocus();
        }
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
        long deadline = System.nanoTime() + MESH_BUDGET_NANOS;
        Iterator<BlockPos> iterator = MESH_QUEUE.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && (rebuilt == 0 || System.nanoTime() < deadline)) {
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
            Set<BlockPos> positions = CHUNKS.get(key);
            if (positions == null || positions.isEmpty()) {
                CHUNK_BATCHES.remove(key);
                rebuilt++;
                continue;
            }
            List<ChunkFace> faces = new ArrayList<>();
            for (BlockPos position : positions) {
                CachedVolume cached = VOLUMES.get(position);
                if (cached == null) continue;
                for (MicrovoxelGreedyMesher.Face face : cached.mesh) {
                    faces.add(new ChunkFace(position, cached, face));
                }
            }
            CHUNK_BATCHES.put(key, new ChunkBatch((int) (key >> 32), (int) key, faces));
            rebuilt++;
        }
    }

    private static void clearVolumes() {
        VOLUMES.clear();
        CHUNKS.clear();
        MESH_QUEUE.clear();
        FAR_THROTTLED_REBUILDS.clear();
        HELD_WORK_SECTIONS.clear();
        PENDING_WORK_MESH.clear();
        workFocus = null;
        DELAYED_BOUNDARY_REBUILDS.clear();
        CHUNK_BATCHES.clear();
        CHUNK_BATCH_QUEUE.clear();
        SECTION_REBUILD_QUEUE.clear();
        PROBE_EMITTED.clear();
        CLIENT_DICTIONARY.clear();
        AUTHORITATIVE_VOLUMES.clear();
        PENDING_EDITS.clear();
        PENDING_PLACEMENTS.clear();
        PENDING_LIGHT_CHECKS.clear();
        CLIENT_FLUIDS.clear();
        predictedFluidAt = null;
        MESHING_JOBS.clear();
        MESH_DIRTY_DURING_BUILD.clear();
        MISSING_MARKERS.clear();
        MicrovoxelSectionModel.clearThreadCaches();
        MicrovoxelItemModel.clearCache();
    }

    public static void clearChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Set<BlockPos> positions = CHUNKS.remove(key);
        if (positions != null) {
            for (BlockPos pos : positions) {
                VOLUMES.remove(pos);
                AUTHORITATIVE_VOLUMES.remove(pos);
                PENDING_PLACEMENTS.remove(pos);
                MISSING_MARKERS.remove(pos);
                PENDING_LIGHT_CHECKS.remove(pos);
                CLIENT_FLUIDS.remove(pos);
                if (workFocus != null && workFocus.equals(pos)) {
                    setWorkFocus(null);
                }
                queueRebuild(pos);
            }
            // Drop predictions touching the cleared chunk (brush strokes span chunks, so any
            // covered volume counts) and rebase survivors onto the post-clear state.
            java.util.ArrayList<Long> droppedTx = new java.util.ArrayList<>();
            for (var entry : PENDING_EDITS.entrySet()) {
                if (coversChunk(entry.getValue(), chunkX, chunkZ)) droppedTx.add(entry.getKey());
            }
            for (Long txId : droppedTx) {
                PendingEdit dropped = PENDING_EDITS.remove(txId);
                if (dropped == null) continue;
                settlePredicted(dropped.position());
                if (dropped.brushTargets() != null) {
                    java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();
                    seen.add(dropped.position());
                    for (BrushOp op : dropped.brushTargets()) {
                        if (seen.add(op.position()) && VOLUMES.containsKey(op.position())) {
                            settlePredicted(op.position());
                        }
                    }
                }
            }
            CHUNK_BATCH_QUEUE.add(key);
        }
    }

    /** True when any volume covered by a prediction sits in the given chunk. */
    private static boolean coversChunk(PendingEdit pending, int chunkX, int chunkZ) {
        if ((pending.position().getX() >> 4) == chunkX && (pending.position().getZ() >> 4) == chunkZ) {
            return true;
        }
        if (pending.brushTargets() == null) return false;
        for (BrushOp op : pending.brushTargets()) {
            if ((op.position().getX() >> 4) == chunkX && (op.position().getZ() >> 4) == chunkZ) {
                return true;
            }
        }
        return false;
    }

    private static void addToChunk(BlockPos position) {
        CHUNKS.computeIfAbsent(chunkKey(position.getX() >> 4, position.getZ() >> 4), ignored -> new HashSet<>())
                .add(position);
    }

    private static void removeFromChunk(BlockPos position) {
        long key = chunkKey(position.getX() >> 4, position.getZ() >> 4);
        Set<BlockPos> indexed = CHUNKS.get(key);
        if (indexed == null) return;
        indexed.remove(position);
        if (indexed.isEmpty()) CHUNKS.remove(key);
        CHUNK_BATCH_QUEUE.add(key);
    }

    private static int floorChunk(double blockCoordinate) {
        return ((int) Math.floor(blockCoordinate)) >> 4;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record MissingMarker(int firstSeenTick, int nextAttemptTick, int attempts) {
    }

    private static VoxelShape combineShapes(VoxelShape[] shapes, int start, int end) {
        if (start >= end) return Shapes.empty();
        if (start == end - 1) return shapes[start];
        int mid = (start + end) / 2;
        return Shapes.or(combineShapes(shapes, start, mid), combineShapes(shapes, mid, end));
    }

    private static VoxelShape buildShape(MicrovoxelVolume volume) {
        MicrovoxelVolume.CollisionPlan plan = volume.collisionPlan();
        if (plan.backend() == MicrovoxelVolume.CollisionBackend.GRID) {
            BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(
                    MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION);
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                if (volume.occupied(cell)) {
                    discrete.fill(MicrovoxelVolume.x(cell), MicrovoxelVolume.y(cell), MicrovoxelVolume.z(cell));
                }
            }
            return CubeVoxelShapeInvoker.eclipse$create(discrete);
        }

        List<MicrovoxelVolume.Cuboid> cuboids = plan.cuboids();
        if (cuboids.isEmpty()) return Shapes.empty();
        VoxelShape[] parts = new VoxelShape[cuboids.size()];
        for (int index = 0; index < cuboids.size(); index++) {
            MicrovoxelVolume.Cuboid cuboid = cuboids.get(index);
            parts[index] = Shapes.box(
                    cuboid.minX() / 16.0, cuboid.minY() / 16.0, cuboid.minZ() / 16.0,
                    cuboid.maxX() / 16.0, cuboid.maxY() / 16.0, cuboid.maxZ() / 16.0);
        }
        return combineShapes(parts, 0, parts.length).optimize();
    }

    private static BlockPos readPosition(DataInputStream input) throws IOException {
        return new BlockPos(input.readInt(), input.readInt(), input.readInt());
    }

    private static MicrovoxelVolume readRawVolume(DataInputStream input) throws IOException {
        int revision = readVarInt(input);
        int paletteSize = readVarInt(input);
        if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
            throw new IOException("Invalid palette size");
        }
        List<String> palette = new ArrayList<>(paletteSize);
        for (int index = 0; index < paletteSize; index++) palette.add(readUtf8(input));
        byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
        int encoding = input.readUnsignedByte();
        if (encoding == 0) {
            if (input.readNBytes(cells, 0, cells.length) != cells.length) {
                throw new EOFException("Truncated cells");
            }
        } else if (encoding == 1) {
            int runs = readVarInt(input);
            int cursor = 0;
            for (int run = 0; run < runs; run++) {
                int length = readVarInt(input);
                byte material = input.readByte();
                if (length < 1 || cursor + length > cells.length) {
                    throw new IOException("Invalid RLE run");
                }
                java.util.Arrays.fill(cells, cursor, cursor + length, material);
                cursor += length;
            }
            if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
        } else {
            throw new IOException("Unknown cell encoding");
        }
        return new MicrovoxelVolume(revision, palette, cells);
    }

    private static void applyTransactionChange(TransactionChange change) {
        MicrovoxelVolume volume = change.volume();
        if (volume != null && !isFreshAuthoritative(change.position(), volume.revision())) return;
        acceptAuthoritative(change.position(), volume);
    }

    /**
     * Installs authoritative fluid levels: stale revisions drop silently, fresh ones replace
     * the view and rebuild the section (surface heights changed). Levels outside 0..16 or a
     * wrong cell count reject the whole packet, like volumes do.
     */
    private static void acceptFluid(BlockPos position, int revision, boolean lava, byte[] levels) {
        if (levels.length != MicrovoxelVolume.CELL_COUNT) return;
        FluidView current = CLIENT_FLUIDS.get(position);
        if (current != null && !MicrovoxelRevision.isNewer(revision, current.revision())) return;
        CLIENT_FLUIDS.put(position, new FluidView(levels, revision, lava));
        MicrovoxelClientMetrics.inc("fluid.received");
        queueChunkBatch(position);
        scheduleBoundaryRebuild(position);
    }

    private static void dropFluid(BlockPos position) {
        if (CLIENT_FLUIDS.remove(position) != null) {
            queueChunkBatch(position);
            scheduleBoundaryRebuild(position);
        }
    }

    /** Raw authoritative levels for rendering and physics refinement, or null when dry. */
    public static FluidView fluidAt(BlockPos position) {
        return CLIENT_FLUIDS.get(position.immutable());
    }

    /**
     * Client mirror of the server voxel-exact water override: true only when vanilla sees
     * water but feet, mid-body and eyes all stand on dry 1/16 cells of marker-fluid volumes
     * with no vanilla water anywhere in the bounding box. Callers apply it to submersion,
     * height and eye reads together, never partially.
     */
    public static boolean shouldIgnoreWater(net.minecraft.world.entity.Entity entity) {
        if (CLIENT_FLUIDS.isEmpty()) return false;
        if (!(entity.level() instanceof net.minecraft.client.multiplayer.ClientLevel level)) {
            return false;
        }
        net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(0.001);
        if (box.getXsize() > 4.0 || box.getYsize() > 4.0 || box.getZsize() > 4.0) return false;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) return false;
        boolean touchedMarkerFluid = false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    net.minecraft.world.level.block.state.BlockState state =
                            level.getBlockState(pos);
                    if (state.getFluidState().isEmpty()) continue;
                    if (!MicrovoxelBlocks.isMarker(state)) return false;
                    touchedMarkerFluid = true;
                }
            }
        }
        if (!touchedMarkerFluid) return false;
        net.minecraft.world.phys.Vec3 feet = entity.position();
        net.minecraft.world.phys.Vec3 eye = feet.add(0.0, entity.getEyeHeight(), 0.0);
        net.minecraft.world.phys.Vec3 mid = feet.add(0.0, entity.getBbHeight() / 2.0, 0.0);
        return !cellWet(feet) && !cellWet(mid) && !cellWet(eye);
    }

    /**
     * Client mirror of the server voxel-lava height: max lava fraction touching the bounding
     * box, or -1 when vanilla already sees lava or nothing does. Vanilla damage, ignition
     * and fog then apply through the standard reads on both sides.
     */
    public static double voxelLavaHeight(net.minecraft.world.entity.Entity entity) {
        if (CLIENT_FLUIDS.isEmpty()) return -1.0;
        if (!(entity.level() instanceof net.minecraft.client.multiplayer.ClientLevel level)) {
            return -1.0;
        }
        net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(0.001);
        if (box.getXsize() > 4.0 || box.getYsize() > 4.0 || box.getZsize() > 4.0) return -1.0;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) return -1.0;
        double height = -1.0;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    if (!level.getFluidState(pos).isEmpty()
                            && !level.getFluidState(pos).is(
                                    net.minecraft.world.level.material.Fluids.WATER)) {
                        return -1.0;
                    }
                    if (!MicrovoxelBlocks.isMarker(level.getBlockState(pos))) continue;
                    FluidView view = CLIENT_FLUIDS.get(pos);
                    if (view == null || !view.lava()) continue;
                    int x0 = Math.max(0, (int) Math.floor((box.minX - bx) * 16.0));
                    int x1 = Math.min(15, (int) Math.floor((box.maxX - bx) * 16.0 - 1.0E-7));
                    int y0 = Math.max(0, (int) Math.floor((box.minY - by) * 16.0));
                    int y1 = Math.min(15, (int) Math.floor((box.maxY - by) * 16.0 - 1.0E-7));
                    int z0 = Math.max(0, (int) Math.floor((box.minZ - bz) * 16.0));
                    int z1 = Math.min(15, (int) Math.floor((box.maxZ - bz) * 16.0 - 1.0E-7));
                    for (int cx = x0; cx <= x1; cx++) {
                        for (int cy = y0; cy <= y1; cy++) {
                            for (int cz = z0; cz <= z1; cz++) {
                                int wet = view.level(MicrovoxelVolume.index(cx, cy, cz));
                                if (wet <= 0) continue;
                                double cell = wet / 16.0;
                                if (cell > height) height = cell;
                            }
                        }
                    }
                }
            }
        }
        return height;
    }

    /** Lava height at one exact position, or -1 when dry. Single-cell eye-check path. */
    public static double voxelLavaHeightAt(net.minecraft.world.entity.Entity entity,
                                           net.minecraft.world.phys.Vec3 exact) {
        if (CLIENT_FLUIDS.isEmpty()) return -1.0;
        if (!(entity.level() instanceof net.minecraft.client.multiplayer.ClientLevel)) {
            return -1.0;
        }
        return voxelLavaHeightAt(entity.level(), exact);
    }

    /** Level-based core shared by entity and camera refinements. */
    public static double voxelLavaHeightAt(net.minecraft.world.level.Level level,
                                           net.minecraft.world.phys.Vec3 exact) {
        if (CLIENT_FLUIDS.isEmpty() || level == null) return -1.0;
        BlockPos pos = BlockPos.containing(exact);
        if (!MicrovoxelBlocks.isMarker(level.getBlockState(pos))) return -1.0;
        FluidView view = CLIENT_FLUIDS.get(pos);
        if (view == null || !view.lava()) return -1.0;
        int cellX = (int) Math.floor((exact.x - pos.getX()) * 16.0);
        int cellY = (int) Math.floor((exact.y - pos.getY()) * 16.0);
        int cellZ = (int) Math.floor((exact.z - pos.getZ()) * 16.0);
        if (cellX < 0 || cellX > 15 || cellY < 0 || cellY > 15 || cellZ < 0 || cellZ > 15) {
            return -1.0;
        }
        int wet = view.level(MicrovoxelVolume.index(cellX, cellY, cellZ));
        return wet > 0 ? wet / 16.0 : -1.0;
    }

    /** True when the exact 1/16 cell under a precise position holds water. */
    public static boolean cellWet(net.minecraft.world.phys.Vec3 exact) {
        BlockPos pos = BlockPos.containing(exact);
        FluidView view = CLIENT_FLUIDS.get(pos);
        if (view == null) return false;
        int cellX = (int) Math.floor((exact.x - pos.getX()) * 16.0);
        int cellY = (int) Math.floor((exact.y - pos.getY()) * 16.0);
        int cellZ = (int) Math.floor((exact.z - pos.getZ()) * 16.0);
        if (cellX < 0 || cellX > 15 || cellY < 0 || cellY > 15 || cellZ < 0 || cellZ > 15) {
            return false;
        }
        return view.level(MicrovoxelVolume.index(cellX, cellY, cellZ)) > 0;
    }

    /**
     * Fluid level at global micro coordinates, transparent across volume borders (floor
     * division, like the solid sampler). Returns 0 outside known fluid data.
     */
    public static int fluidLevelAt(BlockPos base, int x, int y, int z) {
        int offsetX = Math.floorDiv(x, 16);
        int offsetY = Math.floorDiv(y, 16);
        int offsetZ = Math.floorDiv(z, 16);
        BlockPos target = offsetX == 0 && offsetY == 0 && offsetZ == 0 ? base
                : new BlockPos(base.getX() + offsetX, base.getY() + offsetY, base.getZ() + offsetZ);
        FluidView view = CLIENT_FLUIDS.get(target);
        if (view == null) return 0;
        return view.level(MicrovoxelVolume.index(
                Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16)));
    }

    /**
     * Mirror of the server RLE fluid codec. Duplicated deliberately: the two modules cannot
     * share code, and the build fails closed if either side drifts (see protocol parity).
     * Public for unit tests.
     */
    public static byte[] decodeLevels(byte[] encoded) throws IOException {
        try (DataInputStream wrapped = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int total = readVarInt(wrapped);
            if (total < 0 || total > 65536) throw new IOException("Invalid fluid level count");
            byte[] levels = new byte[total];
            int cursor = 0;
            while (cursor < total) {
                int run = readVarInt(wrapped);
                int level = wrapped.readUnsignedByte();
                if (run < 1 || cursor + run > total || level > 16) {
                    throw new IOException("Invalid fluid level run");
                }
                java.util.Arrays.fill(levels, cursor, cursor + run, (byte) level);
                cursor += run;
            }
            if (wrapped.read() != -1) throw new IOException("Trailing fluid level bytes");
            return levels;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt is too big");
        }
        return value;
    }

    private static String readUtf8(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated UTF-8 value");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record TransactionChange(BlockPos position, MicrovoxelVolume volume) {
    }

    private static final java.util.concurrent.ExecutorService SHAPE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Microvoxel-Shape-Generator");
                thread.setDaemon(true);
                return thread;
            });

    public static net.minecraft.world.level.block.state.BlockState getBaseBlockState(BlockPos pos) {
        CachedVolume cached = VOLUMES.get(pos.immutable());
        if (cached == null) return null;
        return cached.getBaseBlockState();
    }

    /** Resolves the actual palette material immediately behind a rendered micro-surface. */
    public static net.minecraft.world.level.block.state.BlockState materialStateAtSurface(
            BlockPos pos,
            net.minecraft.world.phys.Vec3 worldPosition,
            net.minecraft.world.phys.Vec3 outwardNormal
    ) {
        CachedVolume cached = VOLUMES.get(pos.immutable());
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
        return CachedVolume.parseBlockState(cached.volume.palette().get(material));
    }

    public static final class CachedVolume {
        public final BlockPos position;
        public volatile MicrovoxelVolume volume;
        private final VoxelShape fallback;
        private volatile VoxelShape shape;
        private volatile int shapeRevision;
        private volatile boolean shapeGenerating = false;
        public volatile List<MicrovoxelGreedyMesher.Face> mesh = List.of();
        /** Revision the near (stride-1) mesh was built for; 0 means never built. */
        volatile int meshRevision;
        /** Stride-2 LOD mesh for mid-range viewing, with its own revision gate. */
        volatile List<MicrovoxelGreedyMesher.Face> midMesh;
        volatile int midMeshRevision = Integer.MIN_VALUE;
        /** Stride-4 silhouette mesh for far viewing, with its own revision gate. */
        volatile List<MicrovoxelGreedyMesher.Face> farMesh;
        volatile int farMeshRevision = Integer.MIN_VALUE;
        /** Display tier owned by the LOD review; rebuilds compile for this stride. */
        volatile ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier lodTier =
                ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.NEAR;
        /** GPU render-pass flags snapshot, refreshed on every displayed volume install. */
        volatile int renderFlags = MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
        /** Light-seal snapshot twin of renderFlags: drives explicit engine rechecks on flips. */
        volatile boolean lightSealed;
        /** Cached light-engine parent state with its revision gate (null = transparent). */
        volatile net.minecraft.world.level.block.state.BlockState lightParent;
        volatile int lightParentRevision = Integer.MIN_VALUE;
        private net.minecraft.world.level.block.state.BlockState baseBlockState;

        private CachedVolume(BlockPos position, MicrovoxelVolume volume, VoxelShape fallback) {
            this.position = position.immutable();
            this.volume = volume;
            this.fallback = fallback;
        }

        private synchronized void updateVolume(MicrovoxelVolume replacement) {
            this.volume = replacement;
            this.baseBlockState = null;
            if (!shapeGenerating) triggerAsyncShapeGen();
        }

        public net.minecraft.world.level.block.state.BlockState getBaseBlockState() {
            if (baseBlockState == null) {
                String matStr = null;
                for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                    if (volume.occupied(cell)) {
                        matStr = volume.material(cell);
                        break;
                    }
                }
                if (matStr != null && !matStr.isEmpty()) {
                    baseBlockState = parseBlockState(matStr);
                } else {
                    baseBlockState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                }
            }
            return baseBlockState;
        }

        private static net.minecraft.world.level.block.state.BlockState parseBlockState(String value) {
            return MicrovoxelSectionModel.parseBlockState(value);
        }

        public VoxelShape getShape() {
            VoxelShape s = shape;
            if (s == null || shapeRevision != volume.revision()) {
                triggerAsyncShapeGen();
                if (s == null) return fallback != null ? fallback : Shapes.block();
            }
            return s;
        }

        private synchronized void triggerAsyncShapeGen() {
            if (shapeGenerating || shapeRevision == volume.revision()) return;
            shapeGenerating = true;
            MicrovoxelVolume snapshot = volume.copy();
            int requestedRevision = snapshot.revision();
            SHAPE_EXECUTOR.submit(() -> {
                try {
                    long start = System.nanoTime();
                    VoxelShape newShape = buildShape(snapshot);
                    long durationNs = System.nanoTime() - start;
                    Minecraft.getInstance().execute(() -> {
                        if (volume.revision() == requestedRevision) {
                            this.shape = newShape;
                            this.shapeRevision = requestedRevision;
                        }
                        shapeGenerating = false;
                        if (shapeRevision != volume.revision()) triggerAsyncShapeGen();
                    });
                    EclipseClientMod.LOGGER.info(
                            "[MICROVOXEL-PERF] ASYNC_SHAPE_GEN pos={} | Duration: {}us | Backend: {}",
                            position.toShortString(), String.format("%.2f", durationNs / 1000.0),
                            snapshot.collisionPlan().backend());
                } catch (Throwable t) {
                    EclipseClientMod.LOGGER.error("Failed to generate async shape", t);
                    Minecraft.getInstance().execute(() -> shapeGenerating = false);
                }
            });
        }
    }

    public static final class ChunkBatch {
        private final int chunkX;
        private final int chunkZ;
        private final List<ChunkFace> faces;

        private ChunkBatch(int chunkX, int chunkZ, List<ChunkFace> faces) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.faces = List.copyOf(faces);
        }

        public List<ChunkFace> faces() {
            return faces;
        }

        private double distanceSquaredTo(double blockX, double blockZ) {
            double dx = (chunkX << 4) + 8.0 - blockX;
            double dz = (chunkZ << 4) + 8.0 - blockZ;
            return dx * dx + dz * dz;
        }
    }

    public record ChunkFace(BlockPos position, CachedVolume cached, MicrovoxelGreedyMesher.Face face) {
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

    /**
     * One unacknowledged client prediction. Single-cell edits carry their operation inline;
     * brush edits additionally carry every affected cell across all touched volumes.
     * Entries expire via {@link #expirePendingEdits()} so a lost edit-result packet can never
     * pin a stale preview forever.
     */
    private record PendingEdit(long transactionId, BlockPos position, int cell, String material,
                               List<BrushOp> brushTargets, int createdTick) {
        PendingEdit {
            position = position.immutable();
            if (material == null) material = "";
        }

        boolean covers(BlockPos volume) {
            if (position.equals(volume)) return true;
            if (brushTargets == null) return false;
            for (BrushOp op : brushTargets) {
                if (op.position().equals(volume)) return true;
            }
            return false;
        }

        void collectOps(BlockPos volume, List<MicrovoxelPrediction.PredictedOp> out) {
            if (brushTargets != null) {
                for (BrushOp op : brushTargets) {
                    if (op.position().equals(volume)) {
                        out.add(new MicrovoxelPrediction.PredictedOp(op.cell(), op.material()));
                    }
                }
            } else if (position.equals(volume)) {
                out.add(new MicrovoxelPrediction.PredictedOp(cell, material));
            }
        }
    }

    private static final int PENDING_EDIT_TIMEOUT_TICKS = 200;
    private static final int MAX_MISSING_MARKERS = 256;
    private static final int MISSING_MARKER_MAX_ATTEMPTS = 4;
}
