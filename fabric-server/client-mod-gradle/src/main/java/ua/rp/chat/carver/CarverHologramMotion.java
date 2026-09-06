package ua.rp.chat.carver;

/**
 * Easing curves for the carver hologram flights.
 *
 * <p>Both the rise and the fall start sharp, breathe out towards the middle and pick
 * up speed again towards the end: fast ends keep the motion snappy while the soft
 * middle keeps it readable instead of harsh. The impact lands exactly when the fall
 * progress reaches 1, so dust, shake and sound fire on the touchdown tick itself.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverHologramMotion {
    /** Rise duration in client ticks: brisk but readable. */
    public static final int RISE_TICKS = 20;
    /** Fall duration in client ticks: deliberately shorter, the stone drops. */
    public static final int FALL_TICKS = 6;
    /**
     * Ease depth in (0, 1): velocity swings between {@code 1 - depth} mid-flight and
     * {@code 1 + depth} at the ends. 0.6 reads as lively without snapping.
     */
    public static final double EASE_DEPTH = 0.6;

    private CarverHologramMotion() {
    }

    /**
     * Eased progress for a linear 0..1 flight clock: {@code t + depth * sin(2 pi t) / 2 pi}.
     * Starts and ends exactly on the linear schedule (endpoints match), runs fast at both
     * ends and slow in the middle, and stays strictly monotonic for any depth below 1.
     */
    public static double ease(double t) {
        return ease(t, EASE_DEPTH);
    }

    static double ease(double t, double depth) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t + depth * Math.sin(2.0 * Math.PI * t) / (2.0 * Math.PI);
    }

    /** Instantaneous velocity of the easing (derivative): above 1 at the ends, below 1 mid-way. */
    public static double velocity(double t) {
        return velocity(t, EASE_DEPTH);
    }

    static double velocity(double t, double depth) {
        return 1.0 + depth * Math.cos(2.0 * Math.PI * t);
    }

    /**
     * Render-thread interpolation between two tick snapshots over our own tick clock:
     * 0 right after the tick, 1 when the next tick is due. Clamped, so hitches and
     * pauses freeze the picture instead of extrapolating it. Version-proof by design:
     * no engine partial-tick API is involved on either end.
     */
    public static double renderPartial(long lastTickNanos) {
        if (lastTickNanos <= 0L) return 1.0;
        double elapsed = (System.nanoTime() - lastTickNanos) / 50_000_000.0;
        if (!(elapsed >= 0.0)) return 0.0;
        return Math.min(1.0, elapsed);
    }

    /** Frame value between two tick snapshots at the given partial. Pure. */
    public static double lerpTick(double previous, double current, double partial) {
        if (!(partial > 0.0)) return previous;
        if (!(partial < 1.0)) return current;
        return previous + (current - previous) * partial;
    }
}
