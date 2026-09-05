package ua.rp.chat.carver;

import java.util.List;

/**
 * Workload math for a drafting session: how long the simulated carving takes and
 * how much stamina it costs. Labour composes from independent factors instead of
 * one flat rate, so different jobs feel different:
 *
 * <ul>
 *   <li>volume: carved cells over the reference pace, plus flat setup per session;</li>
 *   <li>material: vanilla hardness multiplier resolved by the manager;</li>
 *   <li>detail: scattered single cells cost more each (tool repositioning) than a
 *   solid mass, measured by the bounding-box fill ratio;</li>
 *   <li>depth: carving through the whole block costs more than a shallow relief,
 *   measured by the used layer span.</li>
 * </ul>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class DraftEstimate {
    /** Cells carved per simulated second at reference pace. */
    public static final double CELLS_PER_SECOND = 25.0;
    /** Flat setup per session in seconds: scaffolding, marking, tool prep. */
    public static final double SETUP_SECONDS = 5.0;
    /** Extra cost weight of scattered detail over a solid mass. */
    public static final double DETAIL_WEIGHT = 0.5;
    /** Extra cost weight of full-depth carving over a shallow relief. */
    public static final double DEPTH_WEIGHT = 0.5;
    /** Percent of the 100-point stamina pool burned per carved cell at reference. */
    public static final double STAMINA_PER_CELL = 35.0 / 640.0;
    public static final double MIN_WORK_SECONDS = 5.0;
    public static final double MAX_WORK_SECONDS = 300.0;
    public static final double MAX_STAMINA_COST = 90.0;
    public static final int TICKS_PER_SECOND = 20;

    private DraftEstimate() {
    }

    /**
     * Full work time: setup plus volume paced by material, detail and depth.
     * Every factor is 1.0 for the reference job (solid mass, one layer, stone
     * pace), so the baseline stays readable while real jobs spread out.
     */
    public static double workSeconds(int cells, double fillRatio, int depthSpan,
                                     double materialMult) {
        if (cells <= 0) return 0.0;
        double detail = 1.0 + DETAIL_WEIGHT * (1.0 - clamp01(fillRatio));
        double depth = 1.0 + DEPTH_WEIGHT * (Math.max(1, Math.min(16, depthSpan)) / 16.0);
        double seconds = SETUP_SECONDS
                + (cells / CELLS_PER_SECOND) * materialMult * detail * depth;
        return Math.min(MAX_WORK_SECONDS, Math.max(MIN_WORK_SECONDS, seconds));
    }

    public static int workTicks(int cells, double fillRatio, int depthSpan,
                                double materialMult) {
        return (int) Math.round(workSeconds(cells, fillRatio, depthSpan, materialMult)
                * TICKS_PER_SECOND);
    }

    /** Stamina follows the same effort without the flat setup. */
    public static double staminaCost(int cells, double fillRatio, int depthSpan,
                                     double materialMult) {
        if (cells <= 0) return 0.0;
        double detail = 1.0 + DETAIL_WEIGHT * (1.0 - clamp01(fillRatio));
        double depth = 1.0 + DEPTH_WEIGHT * (Math.max(1, Math.min(16, depthSpan)) / 16.0);
        return Math.min(MAX_STAMINA_COST, cells * STAMINA_PER_CELL * materialMult * detail * depth);
    }

    /** Share of the draft bounding box actually marked, 0..1. Pure. */
    public static double fillRatio(List<Integer> cells) {
        if (cells == null || cells.isEmpty()) return 0.0;
        int x0 = 16;
        int y0 = 16;
        int z0 = 16;
        int x1 = -1;
        int y1 = -1;
        int z1 = -1;
        for (int cell : cells) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            if (x < x0) x0 = x;
            if (y < y0) y0 = y;
            if (z < z0) z0 = z;
            if (x > x1) x1 = x;
            if (y > y1) y1 = y;
            if (z > z1) z1 = z;
        }
        long box = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (box <= 0) return 0.0;
        return Math.min(1.0, cells.size() / (double) box);
    }

    /** Used layer count along the shortest bounding-box axis, 1..16. Pure. */
    public static int depthSpan(List<Integer> cells) {
        if (cells == null || cells.isEmpty()) return 1;
        int x0 = 16;
        int y0 = 16;
        int z0 = 16;
        int x1 = -1;
        int y1 = -1;
        int z1 = -1;
        for (int cell : cells) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            if (x < x0) x0 = x;
            if (y < y0) y0 = y;
            if (z < z0) z0 = z;
            if (x > x1) x1 = x;
            if (y > y1) y1 = y;
            if (z > z1) z1 = z;
        }
        int span = Math.min(x1 - x0 + 1, Math.min(y1 - y0 + 1, z1 - z0 + 1));
        return Math.max(1, Math.min(16, span));
    }

    /** 0..1 fraction of the simulated work finished after {@code doneTicks}. */
    public static double progress(int doneTicks, int totalTicks) {
        if (totalTicks <= 0) return 1.0;
        return Math.min(1.0, Math.max(0.0, doneTicks / (double) totalTicks));
    }

    private static double clamp01(double value) {
        if (!(value >= 0.0)) return 0.0;
        return Math.min(1.0, value);
    }
}
