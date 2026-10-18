package ua.rp.chat.carver;

/**
 * Fixed flush phases for throttled section rebuilds during simulated carving: instead
 * of dirtying the render section on every applied slice, the client holds focus
 * sections and flushes them on 0/25/50/75/100% progress. Five section rebuilds per
 * work instead of hundreds, with the progress reading as deliberate work stages.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverWorkPhases {
    public static final double[] MARKS = {0.0, 0.25, 0.5, 0.75, 1.0};

    private CarverWorkPhases() {
    }

    /**
     * Phase marks inside (prev, cur]. Start a session with prev = -1 so the initial
     * 0% flush is counted; repeated or rewound progress flushes nothing.
     */
    public static int phasesCrossed(double prev, double cur) {
        if (!(cur > prev)) return 0;
        int crossed = 0;
        for (double mark : MARKS) {
            if (mark > prev && mark <= cur) crossed++;
        }
        return crossed;
    }

    /** Phase index of a progress value: 0 below 25%, up to 4 at completion. */
    public static int phaseFor(double progress) {
        if (progress >= 1.0) return 4;
        if (progress >= 0.75) return 3;
        if (progress >= 0.5) return 2;
        if (progress >= 0.25) return 1;
        return 0;
    }
}
