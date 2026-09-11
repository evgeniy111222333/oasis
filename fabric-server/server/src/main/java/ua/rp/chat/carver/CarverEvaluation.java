package ua.rp.chat.carver;

import ua.rp.chat.microvoxel.MicrovoxelVolume;

/**
 * Deterministic Phase-4 evaluation of a finished carving. Pure and dependency-free so the same
 * verdict is unit-tested away from a live server.
 *
 * <p>Two independent signals:</p>
 * <ul>
 *   <li><b>Grain respect</b> — every exposed cut face (a removed cell touching a remaining cell)
 *       is compared against the grain field: following a domain seam is clean, slicing straight
 *       through a grain is a fight. Each face is weighted by grain strength, so a strong seam
 *       matters more than a faint one.</li>
 *   <li><b>Stability</b> — the largest connected component of the remaining volume over the total
 *       remaining cells: a piece that stayed one coherent body scores high, a shattered one
 *       scores low.</li>
 * </ul>
 *
 * <p>Fidelity against an uploaded blueprint, when present, folds in as IoU.</p>
 */
public final class CarverEvaluation {
    /** Weight of grain respect in the final score when a grain exists. */
    private static final double GRAIN_WEIGHT = 0.65;
    /** Weight of stability in the final score. */
    private static final double STABILITY_WEIGHT = 0.35;
    /** Weight of blueprint fidelity folded into the score when a blueprint exists. */
    private static final double FIDELITY_WEIGHT = 0.30;

    /** Verdict of one finished piece. */
    public record Result(double grainRespect, double stability, double fidelity,
                         double score, String grade, int mastery, int removedCells,
                         boolean grainless) {
    }

    private CarverEvaluation() {
    }

    /** Evaluates a carve with no blueprint. */
    public static Result evaluate(DraftMask draft, MicrovoxelVolume after,
                                  CarverGrainField.Field grain) {
        return evaluate(draft, after, grain, null);
    }

    /**
     * Evaluates a carve. {@code after} is the volume as it stands once the draft has been
     * removed, {@code grain} is the workpiece field (null for grainless stock) and
     * {@code blueprint} is the uploaded drawing mask or null. Pure.
     */
    public static Result evaluate(DraftMask draft, MicrovoxelVolume after,
                                  CarverGrainField.Field grain, DraftMask blueprint) {
        int removed = draft == null ? 0 : draft.count();
        if (draft == null || after == null || removed == 0) {
            return new Result(0.5, 1.0, blueprint == null ? 0.0 : 0.0, 0.5, "груба", 0, 0,
                    grain == null || !grain.hasGrain());
        }
        boolean grainless = grain == null || !grain.hasGrain();
        double grainRespect = grainless ? 0.5 : grainRespect(draft, after, grain);
        double stability = stability(after);
        double fidelity = blueprint == null ? 0.0 : iou(draft, blueprint);
        double score;
        if (grainless && blueprint == null) {
            score = stability;
        } else if (grainless) {
            score = (1.0 - FIDELITY_WEIGHT) * stability + FIDELITY_WEIGHT * fidelity;
        } else if (blueprint == null) {
            score = GRAIN_WEIGHT * grainRespect + STABILITY_WEIGHT * stability;
        } else {
            double base = GRAIN_WEIGHT * grainRespect + STABILITY_WEIGHT * stability;
            score = (1.0 - FIDELITY_WEIGHT) * base + FIDELITY_WEIGHT * fidelity;
        }
        score = clamp01(score);
        return new Result(grainRespect, stability, fidelity, score,
                CarverGrainMechanics.grade(score),
                CarverGrainMechanics.masteryPoints(score, removed), removed, grainless);
    }

    /**
     * Exposed-face grain respect: each removed cell that borders a remaining cell contributes one
     * face. A face whose neighbour shares the domain is a cross-grain cut (bad); a neighbour in
     * another domain means the cut followed a seam (good). Weighted by the removed cell's grain
     * strength. Pure.
     */
    public static double grainRespect(DraftMask draft, MicrovoxelVolume after,
                                      CarverGrainField.Field grain) {
        double along = 0.0;
        double cross = 0.0;
        for (int cell : draft.cells()) {
            if (!after.occupied(cell) && !draft.get(cell)) continue;
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            int domain = grain.domain(cell);
            double strength = grain.strength(cell);
            for (int dir = 0; dir < 6; dir++) {
                int nx = x + NEIGHBOUR_X[dir];
                int ny = y + NEIGHBOUR_Y[dir];
                int nz = z + NEIGHBOUR_Z[dir];
                if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                double weight = faceWeight(nx, ny, nz, domain, strength, after, grain);
                if (weight > 0.0) {
                    along += weight;
                } else if (weight < 0.0) {
                    cross -= weight;
                }
            }
        }
        return CarverGrainMechanics.respectScore(along, 0.0, cross);
    }

    /**
     * Draft-time grain respect against a solid workpiece (every cell occupied before the carve).
     * A neighbour counts as an exposed cut face when it is not also drafted away, so the value is
     * known the moment the artisan approves the drawing and can pace the work. Pure.
     */
    public static double grainRespect(DraftMask draft, CarverGrainField.Field grain) {
        return CarverGrainMechanics.draftRespect(draft, grain);
    }

    private static final int[] NEIGHBOUR_X = {-1, 1, 0, 0, 0, 0};
    private static final int[] NEIGHBOUR_Y = {0, 0, -1, 1, 0, 0};
    private static final int[] NEIGHBOUR_Z = {0, 0, 0, 0, -1, 1};

    /**
     * Weight of one cut face: positive when the cut followed a seam (the neighbour lies in a
     * different domain), negative when it sliced through the grain. Zero means the neighbour does
     * not form a cut face. Only a still-occupied neighbour forms an exposed cut.
     */
    private static double faceWeight(int x, int y, int z, int domain, double strength,
                                     MicrovoxelVolume after, CarverGrainField.Field grain) {
        int neighbour = x | (z << 4) | (y << 8);
        if (!after.occupied(neighbour)) return 0.0;
        double weight = 0.25 + strength;
        return grain.domain(neighbour) == domain ? -weight : weight;
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
