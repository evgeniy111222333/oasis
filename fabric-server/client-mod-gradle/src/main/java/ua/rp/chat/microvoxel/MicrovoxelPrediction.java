package ua.rp.chat.microvoxel;

import java.util.Collection;
import java.util.List;

/**
 * Replays ordered, unconfirmed edits over the latest authoritative volume.
 *
 * <p>Every predicted click (remove, add or brush cell) is applied locally in transaction order
 * before the server round-trip completes, so rapid editing never visibly stalls on latency.
 * When the authoritative result arrives, still-pending operations are replayed on top of it;
 * a rejected transaction simply drops out of the replayed sequence (rollback).</p>
 */
public final class MicrovoxelPrediction {
    private MicrovoxelPrediction() {
    }

    /**
     * One predicted cell write. An empty material means removal, any other value means placement.
     * Operations must be supplied in ascending transaction order for deterministic replay.
     */
    public record PredictedOp(int cell, String material) {
        public PredictedOp {
            if (material == null) material = "";
        }

        public boolean removal() {
            return material.isEmpty();
        }
    }

    public static MicrovoxelVolume replayRemovals(
            MicrovoxelVolume authoritative,
            Collection<Integer> orderedCells
    ) {
        if (orderedCells == null || orderedCells.isEmpty()) {
            return authoritative == null ? null : authoritative.copy();
        }
        List<PredictedOp> ops = new java.util.ArrayList<>(orderedCells.size());
        for (int cell : orderedCells) ops.add(new PredictedOp(cell, ""));
        return replayEdits(authoritative, ops);
    }

    /**
     * Replays mixed add/remove operations over a copy of the authoritative volume.
     * Removals of already-empty cells and placements into occupied cells are skipped so a
     * stale prediction can never corrupt the preview. Returns {@code null} when the replayed
     * volume ends up completely empty (the marker is then hidden until the server confirms).
     */
    public static MicrovoxelVolume replayEdits(
            MicrovoxelVolume authoritative,
            List<PredictedOp> orderedOps
    ) {
        if (authoritative == null) {
            if (orderedOps == null || orderedOps.isEmpty()) return null;
            // Placement into a not-yet-known volume: predict onto an empty shell. The server
            // result replaces it as soon as the authoritative state arrives.
            MicrovoxelVolume shell = new MicrovoxelVolume(1,
                    new java.util.ArrayList<>(List.of("")), new byte[MicrovoxelVolume.CELL_COUNT]);
            return applyOps(shell, orderedOps);
        }
        if (orderedOps == null || orderedOps.isEmpty()) return authoritative.copy();
        return applyOps(authoritative.copy(), orderedOps);
    }

    private static MicrovoxelVolume applyOps(MicrovoxelVolume predicted, List<PredictedOp> ops) {
        for (PredictedOp op : ops) {
            int cell = op.cell();
            if (cell < 0 || cell >= MicrovoxelVolume.CELL_COUNT) continue;
            if (op.removal()) {
                if (!predicted.occupied(cell)) continue;
                predicted.update(cell, "");
            } else {
                if (predicted.occupied(cell)) continue;
                try {
                    predicted.update(cell, op.material());
                } catch (IllegalStateException paletteFull) {
                    // A full 32-entry palette cannot take the predicted material; keep the
                    // remaining replay intact and let the authoritative result correct the view.
                    continue;
                }
            }
        }
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (predicted.occupied(cell)) return predicted;
        }
        return null;
    }
}
