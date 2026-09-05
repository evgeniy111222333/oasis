package ua.rp.chat.microvoxel.edit;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import ua.rp.chat.microvoxel.MicrovoxelContext;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player edit history with reversible transactions. Every recorded transaction carries the
 * exact "before" state of each affected volume; undo/redo first verifies that the current store
 * still matches the expected snapshot (conflict-safe) and then restores the historical bytes with
 * a fresh revision so in-flight clients converge immediately.
 */
public final class MicrovoxelEditHistory {
    private static final int MAX_HISTORY_DEPTH = 128;

    private final MicrovoxelContext context;
    private final Map<UUID, Deque<EditTransaction>> undoHistory = new HashMap<>();
    private final Map<UUID, Deque<EditTransaction>> redoHistory = new HashMap<>();

    public MicrovoxelEditHistory(MicrovoxelContext context) {
        this.context = context;
    }

    public void recordEdit(
            ServerPlayer player,
            long transactionId,
            MicrovoxelKey key,
            MicrovoxelVolume before,
            MicrovoxelVolume after
    ) {
        recordEdit(player, transactionId,
                List.of(new EditChange(key, copyOrNull(before), copyOrNull(after))));
    }

    public void recordEdit(
            ServerPlayer player,
            long transactionId,
            List<EditChange> changes
    ) {
        if (changes.isEmpty()) return;
        Deque<EditTransaction> undo = undoHistory.computeIfAbsent(
                player.getUUID(), ignored -> new ArrayDeque<>());
        undo.addLast(new EditTransaction(transactionId, List.copyOf(changes)));
        while (undo.size() > MAX_HISTORY_DEPTH) undo.removeFirst();
        redoHistory.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>()).clear();
    }

    public void applyHistory(ServerPlayer player, boolean undo) {
        if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            context.sync().feedback(player,
                    "Undo/redo доступен в creative: survival использует точный материальный баланс.");
            return;
        }
        Map<UUID, Deque<EditTransaction>> sourceMap = undo ? undoHistory : redoHistory;
        Map<UUID, Deque<EditTransaction>> destinationMap = undo ? redoHistory : undoHistory;
        Deque<EditTransaction> source = sourceMap.computeIfAbsent(
                player.getUUID(), ignored -> new ArrayDeque<>());
        EditTransaction transaction = source.peekLast();
        if (transaction == null) {
            context.sync().feedback(player, undo ? "История отмены пуста." : "История повтора пуста.");
            return;
        }
        boolean conflict = false;
        for (EditChange change : transaction.changes()) {
            MicrovoxelVolume expected = undo ? change.after() : change.before();
            if (!sameVolume(context.runtime().store().get(change.key()), expected)) {
                conflict = true;
                break;
            }
        }
        // Undo/redo restores raw bytes past the action gate, so protection is re-checked here
        // per touched volume instead of trusting the recorded transaction.
        if (!conflict) {
            ua.rp.chat.microvoxel.MicrovoxelFlags flags = context.runtime().flags();
            if (flags != null) {
                for (EditChange change : transaction.changes()) {
                    if (flags.isProtected(change.key())) {
                        conflict = true;
                        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.rejected.protected");
                        break;
                    }
                }
            }
        }
        if (conflict) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.rejected.history-conflict");
            context.sync().feedback(player,
                    "Объём уже изменён другим действием; история безопасно остановлена.");
            return;
        }
        // Undo/redo restores exact historical bytes, so it must respect the same per-chunk
        // quota as live edits; otherwise undoing deletes after creating replacements would
        // push a full chunk over MAX_PER_CHUNK.
        for (EditChange change : transaction.changes()) {
            MicrovoxelVolume current = context.runtime().store().get(change.key());
            MicrovoxelVolume desired = undo ? change.before() : change.after();
            boolean createsVolume = current == null && desired != null;
            if (createsVolume && context.runtime().store().countInChunk(
                    change.key().worldId(), change.key().chunkX(), change.key().chunkZ())
                    >= MicrovoxelRuntime.MAX_PER_CHUNK) {
                context.sync().feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
                return;
            }
        }
        source.removeLast();
        List<MicrovoxelProtocol.StateChange> networkChanges =
                new ArrayList<>(transaction.changes().size());
        for (EditChange change : transaction.changes()) {
            MicrovoxelVolume desired = undo ? change.before() : change.after();
            networkChanges.add(restoreHistoricalVolume(
                    change.key(), context.runtime().store().get(change.key()), desired));
        }
        context.sync().broadcastTransaction(transaction.transactionId(), networkChanges);
        Deque<EditTransaction> destination = destinationMap.computeIfAbsent(
                player.getUUID(), ignored -> new ArrayDeque<>());
        destination.addLast(transaction);
        while (destination.size() > MAX_HISTORY_DEPTH) destination.removeFirst();
        context.sync().feedback(player, undo ? "Изменение отменено." : "Изменение повторено.");
    }

    public void onQuit(UUID playerId) {
        undoHistory.remove(playerId);
        redoHistory.remove(playerId);
    }

    private MicrovoxelProtocol.StateChange restoreHistoricalVolume(
            MicrovoxelKey key,
            MicrovoxelVolume current,
            MicrovoxelVolume desired
    ) {
        if (desired == null) {
            context.runtime().projection().dematerialize(key);
            context.collision().invalidate(key);
            return new MicrovoxelProtocol.StateChange(key, null);
        }
        int baseRevision = current == null ? desired.revision() : current.revision();
        int revision = MicrovoxelRevision.next(baseRevision);
        MicrovoxelVolume restored = MicrovoxelVolume.restore(
                revision, desired.palette(), desired.cellsCopy());
        context.runtime().projection().materialize(key, restored);
        // The dematerialize branch above invalidates; the materialize branch must too, or the
        // collision AND light caches keep serving the pre-undo geometry after an undo/redo.
        context.collision().invalidate(key);
        return new MicrovoxelProtocol.StateChange(key, restored);
    }

    /**
     * Null-safe deep copy for history snapshots. Public for unit tests: history identity
     * (conflict detection) depends on exact palette+cell equality, never object identity.
     */
    public static MicrovoxelVolume copyOrNull(MicrovoxelVolume volume) {
        return volume == null ? null : volume.copy();
    }

    /**
     * Conflict predicate: two volumes match only on identical palettes and cells. The revision
     * is deliberately excluded because history restores carry a fresh revision by design.
     */
    public static boolean sameVolume(MicrovoxelVolume left, MicrovoxelVolume right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.palette().equals(right.palette())
                && java.util.Arrays.equals(left.cellsCopy(), right.cellsCopy());
    }

    record EditChange(MicrovoxelKey key, MicrovoxelVolume before, MicrovoxelVolume after) {
    }

    record EditTransaction(long transactionId, List<EditChange> changes) {
    }
}