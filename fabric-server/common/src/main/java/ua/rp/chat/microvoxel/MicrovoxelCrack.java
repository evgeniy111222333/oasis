package ua.rp.chat.microvoxel;

/**
 * Per-cell mining crack state: the cell index to destroy-stage mapping one microvoxel volume
 * keeps while it is being mined. Pure data so the reducer can be unit-tested, and shared so the
 * server and client agree on the stage range.
 *
 * <p>Stage {@code -1} clears a cell; {@code 0..9} select the vanilla {@code destroy_stage_N}
 * sprite. Higher stages are clamped defensively instead of rejected, so a future server sending
 * more stages still renders something sane on an older client.</p>
 */
public final class MicrovoxelCrack {
    /** Highest crack stage (vanilla destroy_stage_0..9). */
    public static final int MAX_STAGE = 9;

    private MicrovoxelCrack() {
    }

    /**
     * Applies one stage update to a {@code cell -> stage} map in place. Returns {@code true} when
     * the map actually changed, so callers only invalidate geometry for real transitions.
     */
    public static boolean apply(java.util.Map<Integer, Integer> cracks, int cell, int stage) {
        if (cracks == null || cell < 0 || cell >= MicrovoxelVolume.CELL_COUNT) return false;
        if (stage < 0) {
            return cracks.remove(cell) != null;
        }
        int clamped = Math.min(MAX_STAGE, stage);
        Integer previous = cracks.put(cell, clamped);
        return previous == null || previous != clamped;
    }
}
