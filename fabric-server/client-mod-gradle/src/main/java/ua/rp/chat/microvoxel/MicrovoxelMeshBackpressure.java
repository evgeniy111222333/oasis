package ua.rp.chat.microvoxel;

/**
 * Admission control for the client microvoxel mesh worker pool.
 *
 * <p>The tick drain used to submit every queued rebuild to an unbounded executor: a cheap-submit
 * tick could enqueue hundreds of jobs (each with its own seven-volume client-thread snapshot),
 * and the per-tick budget only measured submit time, not work. This helper bounds the number of
 * builds in flight so the snapshot cost and the worker backlog stay proportional to the pool size.
 * Deferred positions are left in the dirty queue untouched and retried on the next tick.</p>
 */
public final class MicrovoxelMeshBackpressure {
    private MicrovoxelMeshBackpressure() {
    }

    /**
     * How many queued mesh builds may be submitted this tick.
     *
     * @param inFlight     builds already submitted but not yet installed
     * @param queued       dirty positions waiting to be built
     * @param maxInFlight  the in-flight cap
     * @return the free room under the cap, never more than the queue holds; zero means "hold
     *         everything and let the workers catch up"
     */
    public static int admitCount(int inFlight, int queued, int maxInFlight) {
        if (queued <= 0 || maxInFlight <= 0) return 0;
        int room = maxInFlight - inFlight;
        if (room <= 0) return 0;
        return Math.min(room, queued);
    }
}
