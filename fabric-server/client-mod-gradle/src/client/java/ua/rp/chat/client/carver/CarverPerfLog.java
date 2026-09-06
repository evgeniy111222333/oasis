package ua.rp.chat.client.carver;

/**
 * Detailed frame-budget logging for the drafting session. Entry is broken into
 * stages (camera rig, hologram hide plus entity spawn, screen open), then every
 * tick accumulates client-tick and chalk-merge costs and reports a summary row.
 * Open the log, enter drafting once, and the weak spot reads itself: a huge entry
 * stage pins the click freeze, a growing tick average pins the sustained drop.
 */
public final class CarverPerfLog {
    private static final long REPORT_EVERY_TICKS = 200L;
    private static final double WARN_STAGE_MS = 8.0;
    private static final double WARN_TICK_MS = 5.0;

    private CarverPerfLog() {
    }

    private static long entryStartNanos;
    private static long lastStageNanos;
    private static boolean entryOpen;

    private static long tickCount;
    private static long tickTotalNanos;
    private static long tickMaxNanos;
    private static long mergeCount;
    private static long mergeTotalNanos;
    private static long mergeMaxNanos;
    private static long poseCount;
    private static long poseTotalNanos;
    private static long poseMaxNanos;
    /** Strike-alignment session stats, printed as one row when work ends. */
    private static long sasSettleTicks;
    private static double sasSettleYawErr;
    private static double sasSettlePosErr;
    private static long sasImpacts;
    private static double sasMissMaxCm;
    private static double sasMissTotalCm;

    /** Logs the first chalk frame of the session: the overlay hook is alive. */
    public static void chalkAlive() {
        log("chalk first frame submitted");
    }

    /** Starts the entry breakdown clock on design open. */
    public static void beginEntry() {
        entryStartNanos = System.nanoTime();
        lastStageNanos = entryStartNanos;
        entryOpen = true;
        CarverChalkOverlay.frames = 0L;
        CarverChalkOverlay.submitted = 0L;
        tickCount = 0;
        tickTotalNanos = 0;
        tickMaxNanos = 0;
        mergeCount = 0;
        mergeTotalNanos = 0;
        mergeMaxNanos = 0;
        poseCount = 0;
        poseTotalNanos = 0;
        poseMaxNanos = 0;
        resetSas();
    }

    /** Logs one entry stage with its own cost and the running total. */
    public static void stage(String name) {
        if (!entryOpen) return;
        long now = System.nanoTime();
        double stageMs = (now - lastStageNanos) / 1_000_000.0;
        double totalMs = (now - entryStartNanos) / 1_000_000.0;
        lastStageNanos = now;
        log(String.format(java.util.Locale.ROOT,
                "entry stage %-14s %7.2f ms (total %7.2f ms)%s",
                name, stageMs, totalMs, stageMs >= WARN_STAGE_MS ? " SLOW" : ""));
    }

    /** Closes the entry breakdown once the screen is up. */
    public static void endEntry() {
        if (!entryOpen) return;
        entryOpen = false;
        double totalMs = (System.nanoTime() - entryStartNanos) / 1_000_000.0;
        log(String.format(java.util.Locale.ROOT,
                "entry done total %7.2f ms", totalMs));
    }

    /** Accumulates one client-tick sample; reports a summary row on cadence. */
    public static void tick(long nanos) {
        if (!CarverClientState.inSession()) return;
        tickCount++;
        tickTotalNanos += nanos;
        if (nanos > tickMaxNanos) tickMaxNanos = nanos;
        if (tickCount % REPORT_EVERY_TICKS == 0) {
            double avgMs = tickTotalNanos / 1_000_000.0 / tickCount;
            double maxMs = tickMaxNanos / 1_000_000.0;
            double mergeAvgMs = mergeCount == 0 ? 0.0
                    : mergeTotalNanos / 1_000_000.0 / mergeCount;
            double mergeMaxMs = mergeMaxNanos / 1_000_000.0;
            log(String.format(java.util.Locale.ROOT,
                    "tick x%-4d avg %6.2f ms max %7.2f ms | merges %-4d avg %6.2f ms max %7.2f ms | chalk frames %-5d rects %-6d%s",
                    tickCount, avgMs, maxMs, mergeCount, mergeAvgMs, mergeMaxMs,
                    CarverChalkOverlay.frames, CarverChalkOverlay.submitted,
                    (avgMs >= WARN_TICK_MS || mergeMaxMs >= WARN_STAGE_MS) ? " SLOW" : ""));
        }
    }

    /** Accumulates one chalk merge pass; warns immediately on a slow single pass. */
    public static void merge(long nanos) {
        mergeCount++;
        mergeTotalNanos += nanos;
        if (nanos > mergeMaxNanos) mergeMaxNanos = nanos;
        double ms = nanos / 1_000_000.0;
        if (ms >= WARN_STAGE_MS) {
            log(String.format(java.util.Locale.ROOT,
                    "chalk merge %7.2f ms SLOW (count %d)", ms, mergeCount));
        }
    }

    /** Drops the session counters on close so the next entry starts clean. */
    public static void endSession() {
        entryOpen = false;
        tickCount = 0;
        tickTotalNanos = 0;
        tickMaxNanos = 0;
        mergeCount = 0;
        mergeTotalNanos = 0;
        mergeMaxNanos = 0;
        poseCount = 0;
        poseTotalNanos = 0;
        poseMaxNanos = 0;
        resetSas();
    }

    /** Accumulates one pose-solve sample (tick-rate cache misses only). */
    public static void pose(long nanos) {
        if (!CarverClientState.inSession()) return;
        poseCount++;
        poseTotalNanos += nanos;
        if (nanos > poseMaxNanos) poseMaxNanos = nanos;
    }

    /** Records the settle outcome of one autowalk approach. */
    public static void sasSettle(int ticks, double yawErrDeg, double posErr) {
        sasSettleTicks = ticks;
        sasSettleYawErr = yawErrDeg;
        sasSettlePosErr = posErr;
    }

    /** Records one hammer-to-contact miss measurement in centimeters. */
    public static void sasMiss(double missCm) {
        if (!(missCm >= 0.0)) return;
        sasImpacts++;
        sasMissTotalCm += missCm;
        if (missCm > sasMissMaxCm) sasMissMaxCm = missCm;
    }

    /** Counts an impact that carried no measurable butt (far LOD, fallback pose). */
    public static void noteImpact() {
        sasImpacts++;
    }

    /**
     * Prints the strike-alignment summary row when work ends: settle cost, impact
     * count and butt-to-contact miss. Read it after one carving: a growing miss
     * pins a stance/trajectory regression, a growing settle pins the walk.
     */
    public static void endWorkSession() {
        if (sasImpacts == 0 && sasSettleTicks == 0 && poseCount == 0) return;
        double poseAvgUs = poseCount == 0 ? 0.0 : poseTotalNanos / 1000.0 / poseCount;
        double poseMaxUs = poseMaxNanos / 1000.0;
        double missAvg = sasImpacts == 0 ? 0.0 : sasMissTotalCm / sasImpacts;
        log(String.format(java.util.Locale.ROOT,
                "sas settle=%d ticks yawErr=%.1f deg posErr=%.2f | impacts=%d missAvg=%.2fcm missMax=%.2fcm | pose x%d avg=%.1fus max=%.1fus",
                sasSettleTicks, sasSettleYawErr, sasSettlePosErr,
                sasImpacts, missAvg, sasMissMaxCm,
                poseCount, poseAvgUs, poseMaxUs));
        resetSas();
        poseCount = 0;
        poseTotalNanos = 0;
        poseMaxNanos = 0;
    }

    private static void resetSas() {
        sasSettleTicks = 0;
        sasSettleYawErr = 0.0;
        sasSettlePosErr = 0.0;
        sasImpacts = 0;
        sasMissMaxCm = 0.0;
        sasMissTotalCm = 0.0;
    }

    private static void log(String message) {
        try {
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER-PERF] " + message);
        } catch (RuntimeException ignored) {
        }
    }
}
