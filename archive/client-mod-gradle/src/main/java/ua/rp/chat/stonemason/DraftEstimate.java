package ua.rp.chat.stonemason;

/**
 * Workload math for a drafting session: how long the simulated carving takes and
 * how much stamina it costs. The reference point from the design brief is a 640-cell
 * bathtub at ~15 seconds and ~35% stamina.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired module. Keep both copies
 * byte-identical; {@code verifyStonemasonParity} fails the build on divergence.</p>
 */
public final class DraftEstimate {
    /** Cells carved per simulated second at reference pace. */
    public static final double CELLS_PER_SECOND = 640.0 / 15.0;
    /** Percent of the 100-point stamina pool burned per carved cell. */
    public static final double STAMINA_PER_CELL = 35.0 / 640.0;
    public static final double MIN_WORK_SECONDS = 3.0;
    public static final double MAX_WORK_SECONDS = 120.0;
    public static final double MAX_STAMINA_COST = 90.0;
    public static final int TICKS_PER_SECOND = 20;

    private DraftEstimate() {
    }

    public static double workSeconds(int cells) {
        if (cells <= 0) return 0.0;
        return Math.min(MAX_WORK_SECONDS, Math.max(MIN_WORK_SECONDS, cells / CELLS_PER_SECOND));
    }

    public static int workTicks(int cells) {
        return (int) Math.round(workSeconds(cells) * TICKS_PER_SECOND);
    }

    public static double staminaCost(int cells) {
        if (cells <= 0) return 0.0;
        return Math.min(MAX_STAMINA_COST, cells * STAMINA_PER_CELL);
    }

    /** 0..1 fraction of the simulated work finished after {@code doneTicks}. */
    public static double progress(int doneTicks, int totalTicks) {
        if (totalTicks <= 0) return 1.0;
        return Math.min(1.0, Math.max(0.0, doneTicks / (double) totalTicks));
    }
}
