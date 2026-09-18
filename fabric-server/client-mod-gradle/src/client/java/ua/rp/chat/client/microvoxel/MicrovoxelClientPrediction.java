package ua.rp.chat.client.microvoxel;

import net.minecraft.core.BlockPos;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelPrediction;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optimistic client prediction and its rollback/rebase ledger, extracted from
 * {@link MicrovoxelClientState}.
 *
 * <p>Every locally applied edit is recorded as a {@link PendingEdit}; when the server acks,
 * rejects or times out, the remaining predictions are replayed on top of the authoritative base
 * so a late ACK for an early click never discards later clicks from the same rapid editing
 * stream. Displays are installed through the facade ({@link MicrovoxelClientState#replaceDisplayed})
 * and expired predictions request a targeted resync.</p>
 */
public final class MicrovoxelClientPrediction {
    /** Maximum unacknowledged predictions; older ones are rebased by authoritative state. */
    private static final int MAX_PENDING_EDITS = 32;
    private static final int PENDING_EDIT_TIMEOUT_TICKS = 200;

    private static final LinkedHashMap<Long, PendingEdit> PENDING_EDITS = new LinkedHashMap<>();

    private MicrovoxelClientPrediction() {
    }

    /** True while one transaction still awaits its authoritative edit result. */
    public static boolean isPending(long transactionId) {
        return PENDING_EDITS.containsKey(transactionId);
    }

    /** Number of unacknowledged predictions, for the periodic metrics line. */
    public static int pendingCount() {
        return PENDING_EDITS.size();
    }

    /** Drops every pending prediction (full volume teardown). */
    public static void clear() {
        PENDING_EDITS.clear();
    }

    /**
     * Applies a remove locally before the network round-trip. The authoritative base is retained
     * and every still-pending operation is replayed after each ACK/reject, so rollback never
     * discards later clicks from the same rapid editing stream.
     */
    public static boolean predictRemove(BlockPos position, int cell, long transactionId) {
        BlockPos immutable = position.immutable();
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
        if (cached == null || !cached.volume.occupied(cell)) return false;
        MicrovoxelClientMetrics.inc("predict.remove");
        if (!reservePendingSlot()) return false;
        MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.putIfAbsent(immutable, cached.volume.copy());
        PENDING_EDITS.put(transactionId,
                new PendingEdit(transactionId, immutable, cell, "", null, MicrovoxelClientState.tick()));
        rebuildPredicted(immutable);
        return true;
    }

    /**
     * Applies a single-cell placement locally before the network round-trip. The material is a
     * preview hint derived from the held block; the authoritative server state replaces it as soon
     * as the edit result arrives.
     */
    public static boolean predictAdd(BlockPos position, int cell, String material, long transactionId) {
        BlockPos immutable = position.immutable();
        if (material == null || material.isEmpty()) return false;
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
        if (cached != null && cached.volume.occupied(cell)) return false;
        MicrovoxelClientMetrics.inc("predict.add");
        if (!reservePendingSlot()) return false;
        if (cached != null) {
            MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.putIfAbsent(immutable, cached.volume.copy());
        }
        PENDING_EDITS.put(transactionId,
                new PendingEdit(transactionId, immutable, cell, material, null, MicrovoxelClientState.tick()));
        rebuildPredicted(immutable);
        return true;
    }

    /**
     * Applies a whole brush stroke locally before the network round-trip. Every affected cell
     * across all touched volumes is predicted at once; each touched volume keeps its own
     * authoritative base so later ACKs replay the remaining stroke correctly.
     */
    public static boolean predictBrush(long transactionId, List<MicrovoxelClientState.BrushOp> targets) {
        if (targets == null || targets.isEmpty()) return false;
        MicrovoxelClientMetrics.inc("predict.brush");
        if (!reservePendingSlot()) return false;
        HashSet<BlockPos> bases = new HashSet<>();
        for (MicrovoxelClientState.BrushOp op : targets) {
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(op.position());
            if (cached == null) continue;
            if (op.material().isEmpty() && !cached.volume.occupied(op.cell())) continue;
            if (!op.material().isEmpty() && cached.volume.occupied(op.cell())) continue;
            if (bases.add(op.position())) {
                MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.putIfAbsent(op.position(), cached.volume.copy());
            }
        }
        if (bases.isEmpty()) return false;
        BlockPos anchor = targets.get(0).position();
        PENDING_EDITS.put(transactionId,
                new PendingEdit(transactionId, anchor, -1, "", List.copyOf(targets), MicrovoxelClientState.tick()));
        for (BlockPos base : bases) rebuildPredicted(base);
        return true;
    }

    /** Settles one edit result: rebase the authoritative copy, then replay remaining predictions. */
    public static void reconcileEdit(long transactionId, boolean accepted,
                                     BlockPos position, MicrovoxelVolume authoritative) {
        if (!accepted) MicrovoxelClientMetrics.inc("predict.rollback");
        PendingEdit pending = PENDING_EDITS.remove(transactionId);
        BlockPos target = pending == null ? position.immutable() : pending.position();
        if (!target.equals(position)) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Edit result {} targeted {} instead of {}",
                    transactionId, position.toShortString(), target.toShortString());
        }
        if (!accepted) {
            EclipseClientMod.LOGGER.debug("[MICROVOXEL] Rolling back rejected edit {}", transactionId);
        }
        if (authoritative == null) {
            MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.remove(target);
        } else if (isFreshAuthoritative(target, authoritative.revision())) {
            MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.put(target, authoritative.copy());
        } else {
            // A duplicated/reordered EDIT_RESULT for an older revision must not clobber the newer
            // state we already hold; keep the fresher stored volume and just settle the prediction.
            MicrovoxelClientMetrics.inc("predict.stale_edit_result");
        }

        settlePredicted(target);
        if (pending != null && pending.brushTargets() != null) {
            HashSet<BlockPos> seen = new HashSet<>();
            seen.add(target);
            for (MicrovoxelClientState.BrushOp op : pending.brushTargets()) {
                if (seen.add(op.position())) settlePredicted(op.position());
            }
        }
    }

    /** Shows the replayed preview where predictions remain, else the authoritative state. */
    public static void settlePredicted(BlockPos position) {
        if (hasPendingAt(position)) {
            rebuildPredicted(position);
        } else {
            MicrovoxelVolume authoritative = MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.remove(position);
            MicrovoxelClientState.replaceDisplayed(position, authoritative);
        }
    }

    /**
     * Guards full-state packets (upsert/batch/transaction). Deltas already check revisions; without
     * this, a duplicated or reordered full volume would silently overwrite newer state.
     */
    public static boolean isFreshAuthoritative(BlockPos position, int revision) {
        MicrovoxelVolume base = MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.get(position);
        if (base == null) {
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position);
            base = cached == null ? null : cached.volume;
        }
        if (base == null) return true;
        return MicrovoxelRevision.isNewer(revision, base.revision());
    }

    /** True when any pending transaction still covers this volume. */
    public static boolean hasPendingAt(BlockPos position) {
        for (PendingEdit pending : PENDING_EDITS.values()) {
            if (pending.covers(position)) return true;
        }
        return false;
    }

    /**
     * Replays every still-unacknowledged operation for one volume in transaction order. Add,
     * remove and brush predictions share this path, so a late ACK for an early click never
     * discards the preview of later clicks.
     */
    public static void rebuildPredicted(BlockPos position) {
        MicrovoxelVolume authoritative = MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.get(position);
        List<MicrovoxelPrediction.PredictedOp> ops = new ArrayList<>();
        for (PendingEdit pending : PENDING_EDITS.values()) {
            pending.collectOps(position, ops);
        }
        MicrovoxelVolume predicted = MicrovoxelPrediction.replayEdits(authoritative, ops);
        MicrovoxelClientState.replaceDisplayed(position, predicted);
    }

    /** Rebuilds every volume touched by one pending transaction (brushes span volumes). */
    private static void rebuildPredictedFor(PendingEdit pending) {
        rebuildPredicted(pending.position());
        if (pending.brushTargets() != null) {
            HashSet<BlockPos> seen = new HashSet<>();
            seen.add(pending.position());
            for (MicrovoxelClientState.BrushOp op : pending.brushTargets()) {
                if (seen.add(op.position())) rebuildPredicted(op.position());
            }
        }
    }

    /** Bounds the pending set so a stalled connection cannot accumulate unbounded previews. */
    private static boolean reservePendingSlot() {
        if (PENDING_EDITS.size() < MAX_PENDING_EDITS) return true;
        Long oldest = PENDING_EDITS.keySet().iterator().next();
        PendingEdit dropped = PENDING_EDITS.remove(oldest);
        if (dropped != null) {
            rebuildPredictedFor(dropped);
            MicrovoxelClientState.requestVolumeResync(dropped.position(),
                    "pending prediction budget exceeded");
        }
        return PENDING_EDITS.size() < MAX_PENDING_EDITS;
    }

    /**
     * Fails pending predicted edits closed. A lost edit-result packet must not pin the
     * authoritative copy and predicted mesh forever; expired edits roll back to the last
     * authoritative state and request a targeted volume resync.
     */
    public static void expirePendingEdits() {
        if (PENDING_EDITS.isEmpty()) return;
        ArrayList<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, PendingEdit> entry : PENDING_EDITS.entrySet()) {
            if (MicrovoxelClientState.tick() - entry.getValue().createdTick() >= PENDING_EDIT_TIMEOUT_TICKS) {
                expired.add(entry.getKey());
            }
        }
        for (Long txId : expired) {
            PendingEdit pending = PENDING_EDITS.remove(txId);
            if (pending == null) continue;
            settlePredicted(pending.position());
            if (pending.brushTargets() != null) {
                HashSet<BlockPos> seen = new HashSet<>();
                seen.add(pending.position());
                for (MicrovoxelClientState.BrushOp op : pending.brushTargets()) {
                    if (seen.add(op.position())) settlePredicted(op.position());
                }
            }
            MicrovoxelClientState.requestVolumeResync(pending.position(), "pending edit timed out");
        }
    }

    /** Drops and settles every prediction touching a cleared chunk (brush strokes span chunks). */
    public static void dropChunkPredictions(int chunkX, int chunkZ) {
        ArrayList<Long> droppedTx = new ArrayList<>();
        for (Map.Entry<Long, PendingEdit> entry : PENDING_EDITS.entrySet()) {
            if (coversChunk(entry.getValue(), chunkX, chunkZ)) droppedTx.add(entry.getKey());
        }
        for (Long txId : droppedTx) {
            PendingEdit dropped = PENDING_EDITS.remove(txId);
            if (dropped == null) continue;
            settlePredicted(dropped.position());
            if (dropped.brushTargets() != null) {
                HashSet<BlockPos> seen = new HashSet<>();
                seen.add(dropped.position());
                for (MicrovoxelClientState.BrushOp op : dropped.brushTargets()) {
                    if (seen.add(op.position())
                            && MicrovoxelClientVolumeStore.VOLUMES.containsKey(op.position())) {
                        settlePredicted(op.position());
                    }
                }
            }
        }
    }

    /** True when any volume covered by a prediction sits in the given chunk. */
    private static boolean coversChunk(PendingEdit pending, int chunkX, int chunkZ) {
        if ((pending.position().getX() >> 4) == chunkX && (pending.position().getZ() >> 4) == chunkZ) {
            return true;
        }
        if (pending.brushTargets() == null) return false;
        for (MicrovoxelClientState.BrushOp op : pending.brushTargets()) {
            if ((op.position().getX() >> 4) == chunkX && (op.position().getZ() >> 4) == chunkZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * One unacknowledged client prediction. Single-cell edits carry their operation inline; brush
     * edits additionally carry every affected cell across all touched volumes.
     */
    private record PendingEdit(long transactionId, BlockPos position, int cell, String material,
                               List<MicrovoxelClientState.BrushOp> brushTargets, int createdTick) {
        PendingEdit {
            position = position.immutable();
            if (material == null) material = "";
        }

        boolean covers(BlockPos volume) {
            if (position.equals(volume)) return true;
            if (brushTargets == null) return false;
            for (MicrovoxelClientState.BrushOp op : brushTargets) {
                if (op.position().equals(volume)) return true;
            }
            return false;
        }

        void collectOps(BlockPos volume, List<MicrovoxelPrediction.PredictedOp> out) {
            if (brushTargets != null) {
                for (MicrovoxelClientState.BrushOp op : brushTargets) {
                    if (op.position().equals(volume)) {
                        out.add(new MicrovoxelPrediction.PredictedOp(op.cell(), op.material()));
                    }
                }
            } else if (position.equals(volume)) {
                out.add(new MicrovoxelPrediction.PredictedOp(cell, material));
            }
        }
    }
}
