package ua.rp.chat.carver;

import ua.rp.chat.microvoxel.MicrovoxelVolume;

/**
 * Deterministic Phase-4 evaluation of a finished carving. Pure and dependency-free so the same
 * verdict is unit-tested away from a live server.
 *
 * <p>Signals:</p>
 * <ul>
 *   <li><b>Stability</b> — the largest connected component of the remaining volume over the total
 *       remaining cells: a piece that stayed one coherent body scores high, a shattered one
 *       scores low.</li>
 *   <li><b>Fidelity</b> against an uploaded blueprint, when present, folds in as IoU.</li>
 * </ul>
 */
public final class CarverEvaluation {
    /** Weight of blueprint fidelity folded into the score when a blueprint exists. */
    private static final double FIDELITY_WEIGHT = 0.30;

    /** Verdict of one finished piece. */
    public record Result(double stability, double fidelity, double score, String grade,
                         int mastery, int removedCells) {
    }

    private CarverEvaluation() {
    }

    /** Evaluates a carve with no blueprint. */
    public static Result evaluate(DraftMask draft, MicrovoxelVolume after) {
        return evaluate(draft, after, null);
    }

    /**
     * Evaluates a carve. {@code after} is the volume as it stands once the draft has been removed,
     * and {@code blueprint} is the uploaded drawing mask or null. Pure.
     */
    public static Result evaluate(DraftMask draft, MicrovoxelVolume after, DraftMask blueprint) {
        int removed = draft == null ? 0 : draft.count();
        if (draft == null || after == null || removed == 0) {
            return new Result(1.0, blueprint == null ? 0.0 : 0.0, 0.5, "груба", 0, 0);
        }
        double stability = stability(after);
        double fidelity = blueprint == null ? 0.0 : iou(draft, blueprint);
        double score = blueprint == null
                ? stability
                : (1.0 - FIDELITY_WEIGHT) * stability + FIDELITY_WEIGHT * fidelity;
        score = clamp01(score);
        return new Result(stability, fidelity, score, grade(score),
                masteryPoints(score, removed), removed);
    }

    /** Grade band for a 0..1 score: 0 rough, 1 masterful. Pure. */
    public static String grade(double score) {
        double s = clamp01(score);
        if (s >= 0.80) return "майстерна";
        if (s >= 0.55) return "чиста";
        return "груба";
    }

    /** Mastery points a finished piece is worth for a 0..1 score. Pure. */
    public static int masteryPoints(double score, int cellsRemoved) {
        double s = clamp01(score);
        int base = Math.max(1, cellsRemoved);
        return (int) Math.round(base * (0.35 + 1.30 * s));
    }

    /**
     * Largest connected component of the remaining occupied cells over the total remaining, in
     * 0..1. A hollow or fragmented piece scores below a solid coherent one. Pure.
     */
    public static double stability(MicrovoxelVolume after) {
        int total = 0;
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            if (after.occupied(cell)) total++;
        }
        if (total == 0) return 1.0;
        boolean[] seen = new boolean[DraftMask.CELL_COUNT];
        int largest = 0;
        int[] queue = new int[DraftMask.CELL_COUNT];
        for (int start = 0; start < DraftMask.CELL_COUNT; start++) {
            if (seen[start] || !after.occupied(start)) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int count = 0;
            while (head < tail) {
                int cell = queue[head++];
                count++;
                int x = DraftMask.x(cell);
                int y = DraftMask.y(cell);
                int z = DraftMask.z(cell);
                if (x > 0) tail = push(x - 1, y, z, after, seen, queue, tail);
                if (x < 15) tail = push(x + 1, y, z, after, seen, queue, tail);
                if (y > 0) tail = push(x, y - 1, z, after, seen, queue, tail);
                if (y < 15) tail = push(x, y + 1, z, after, seen, queue, tail);
                if (z > 0) tail = push(x, y, z - 1, after, seen, queue, tail);
                if (z < 15) tail = push(x, y, z + 1, after, seen, queue, tail);
            }
            largest = Math.max(largest, count);
        }
        return largest / (double) total;
    }

    private static int push(int x, int y, int z, MicrovoxelVolume after,
                            boolean[] seen, int[] queue, int tail) {
        int cell = x | (z << 4) | (y << 8);
        if (seen[cell] || !after.occupied(cell)) return tail;
        seen[cell] = true;
        queue[tail] = cell;
        return tail + 1;
    }

    /** Intersection-over-union of the draft and an uploaded blueprint, in 0..1. Pure. */
    public static double iou(DraftMask draft, DraftMask blueprint) {
        if (draft == null || blueprint == null) return 0.0;
        int intersection = 0;
        int union = 0;
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            boolean a = draft.get(cell);
            boolean b = blueprint.get(cell);
            if (a && b) intersection++;
            if (a || b) union++;
        }
        return union == 0 ? 1.0 : intersection / (double) union;
    }

    private static double clamp01(double value) {
        if (!(value > 0.0)) return 0.0;
        return value >= 1.0 ? 1.0 : value;
    }
}
