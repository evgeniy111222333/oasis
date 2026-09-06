package ua.rp.chat.client.microvoxel;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 50ms coalescing window for microvoxel edit actions. Rapid clicks (held-button mining, brush
 * strokes) enqueue here during the tick and flush as one {@link MicrovoxelBatchPayload} at tick
 * end; an isolated click still travels as the classic single-action packet with zero added
 * latency. Every entry keeps its own transaction id, so predictions and edit results match
 * exactly as before — only the envelope changes.
 *
 * <p>Batchable: remove/add/brush/carve (high-frequency cell writes). Everything else (convert,
 * undo/redo, copy/paste, resync, ready) bypasses the window and transmits immediately.</p>
 */
public final class MicrovoxelActionBatcher {
    private static final Deque<QueuedEdit> QUEUE = new ArrayDeque<>();
    private static long nextBatchId = 1L;

    private MicrovoxelActionBatcher() {
    }

    /** Returns true when this action tolerates up to one tick of batching delay. */
    public static boolean isBatchable(int action) {
        return action == MicrovoxelInteractionController.ACTION_REMOVE
                || action == MicrovoxelInteractionController.ACTION_ADD
                || action == MicrovoxelInteractionController.ACTION_BRUSH_REMOVE
                || action == MicrovoxelInteractionController.ACTION_BRUSH_ADD
                || action == MicrovoxelInteractionController.ACTION_CARVE_STANDARD;
    }

    /** Enqueues one predicted edit; the actual packet leaves at the next tick end. */
    public static void enqueue(long transactionId, int action, int x, int y, int z,
                               int cell, int revision) {
        QUEUE.addLast(new QueuedEdit(transactionId, action, x, y, z, cell, revision));
    }

    /** Flushes the window: one single packet for a lone click, batches of 16 otherwise. */
    public static void flush(Minecraft minecraft) {
        if (QUEUE.isEmpty() || minecraft == null || minecraft.player == null) {
            QUEUE.clear();
            return;
        }
        if (!ClientPlayNetworking.canSend(MicrovoxelBatchPayload.TYPE)
                && !ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            QUEUE.clear();
            return;
        }
        Vec3 look = minecraft.player.getViewVector(1.0f).normalize();
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        List<QueuedEdit> drained = new ArrayList<>(QUEUE);
        QUEUE.clear();
        if (drained.size() == 1
                && ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            // Fast path: isolated clicks keep the exact legacy packet and latency.
            QueuedEdit only = drained.get(0);
            MicrovoxelClientMetrics.inc("batch.singles");
            ClientPlayNetworking.send(new MicrovoxelActionPayload(
                    MicrovoxelClientState.PROTOCOL_VERSION, only.transactionId, only.action,
                    only.x, only.y, only.z, only.cell, only.revision,
                    (float) look.x, (float) look.y, (float) look.z,
                    (float) eye.x, (float) eye.y, (float) eye.z));
            return;
        }
        if (!ClientPlayNetworking.canSend(MicrovoxelBatchPayload.TYPE)) {
            // Server without batch support: fall back to individual packets, same order.
            for (QueuedEdit edit : drained) {
                ClientPlayNetworking.send(new MicrovoxelActionPayload(
                        MicrovoxelClientState.PROTOCOL_VERSION, edit.transactionId, edit.action,
                        edit.x, edit.y, edit.z, edit.cell, edit.revision,
                        (float) look.x, (float) look.y, (float) look.z,
                        (float) eye.x, (float) eye.y, (float) eye.z));
            }
            return;
        }
        List<MicrovoxelBatchPayload.Entry> entries = new ArrayList<>(drained.size());
        for (QueuedEdit edit : drained) {
            entries.add(new MicrovoxelBatchPayload.Entry(edit.action, edit.x, edit.y, edit.z,
                    edit.cell, edit.revision, edit.transactionId));
        }
        for (List<MicrovoxelBatchPayload.Entry> chunk
                : MicrovoxelBatchPayload.split(entries, MicrovoxelBatchPayload.MAX_ENTRIES)) {
            MicrovoxelClientMetrics.inc("batch.packets");
            ClientPlayNetworking.send(new MicrovoxelBatchPayload(
                    MicrovoxelClientState.PROTOCOL_VERSION, nextBatchId++,
                    chunk,
                    (float) look.x, (float) look.y, (float) look.z,
                    (float) eye.x, (float) eye.y, (float) eye.z));
        }
        MicrovoxelClientMetrics.add("batch.entries", entries.size());
        if (nextBatchId <= 0L) nextBatchId = 1L;
    }

    /** Drops the window (level change, disconnect). Predictions already expired separately. */
    public static void clear() {
        QUEUE.clear();
    }

    private record QueuedEdit(long transactionId, int action, int x, int y, int z,
                              int cell, int revision) {
    }
}
