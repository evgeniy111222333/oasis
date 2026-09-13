package ua.rp.chat.client.carver;

/**
 * Flight recorder for one autowalk approach: samples walk/settle ticks into a
 * ring buffer and dumps a single compact block to the client log when the walk
 * ends (approve, timeout or abort). A spin-in-place or a wrong-side facing reads
 * directly off the yaw-target columns: oscillating yaw with a flipping target
 * means two steering targets fought, a big yawErr at approve means the approve
 * ran before the body finished turning.
 *
 * <p>Client-only, allocation-free on the hot path (fixed buffer, reused).</p>
 */
public final class CarverSasDiag {
    private static final int SAMPLES = 64;

    private CarverSasDiag() {
    }

    private static final long[] tickAt = new long[SAMPLES];
    private static final byte[] phaseAt = new byte[SAMPLES];
    private static final float[] xAt = new float[SAMPLES];
    private static final float[] zAt = new float[SAMPLES];
    private static final float[] yawAt = new float[SAMPLES];
    private static final float[] targetAt = new float[SAMPLES];
    private static final float[] distAt = new float[SAMPLES];
    private static int count;
    private static long tickCounter;
    private static boolean open;

    /** Walk phases recorded in the buffer. */
    public static final byte WALK = 0;
    public static final byte SEEK = 1;
    public static final byte ALIGN = 2;

    /** Opens a new recording for one approach. */
    public static void start(double standX, double standZ, float strikeYaw) {
        count = 0;
        open = true;
        log(String.format(java.util.Locale.ROOT,
                "sas walk start stand=(%.2f, %.2f) strikeYaw=%.1f",
                standX, standZ, strikeYaw));
    }

    /** Samples one steering tick. Throttled by the caller (walk: every 20th). */
    public static void sample(byte phase, double px, double pz,
                              float yaw, float yawTarget, double dist) {
        if (!open || count >= SAMPLES) return;
        tickAt[count] = tickCounter;
        phaseAt[count] = phase;
        xAt[count] = (float) px;
        zAt[count] = (float) pz;
        yawAt[count] = yaw;
        targetAt[count] = yawTarget;
        distAt[count] = (float) dist;
        count++;
    }

    /** Advances the recorder clock; called once per steering tick. */
    public static void tick() {
        tickCounter++;
    }

    public static boolean isOpen() {
        return open;
    }

    /**
     * Closes the recording with the outcome verdict. Always logs exactly one
     * block: the verdict line plus the compact sample table.
     */
    public static void finish(String outcome, float yawErr, double posErr, int ticks) {
        if (!open) return;
        open = false;
        String verdict = yawErr > 10.0f
                ? "WRONG-FACING (yaw never converged)"
                : "ok";
        log(String.format(java.util.Locale.ROOT,
                "sas walk end outcome=%s ticks=%d yawErr=%.1f posErr=%.2f verdict=%s",
                outcome, ticks, yawErr, posErr, verdict));
        StringBuilder table = new StringBuilder(1024);
        table.append("sas samples tick/phase/x/z/yaw/target/dist:");
        for (int i = 0; i < count; i++) {
            table.append(String.format(java.util.Locale.ROOT,
                    " [%d %s %.2f %.2f %.0f->%.0f %.2f]",
                    tickAt[i], phaseName(phaseAt[i]),
                    xAt[i], zAt[i], yawAt[i], targetAt[i], distAt[i]));
        }
        log(table.toString());
        count = 0;
    }

    /** Closes silently on abort paths that never approve. */
    public static void abort(String reason) {
        if (!open) return;
        open = false;
        log("sas walk abort reason=" + reason);
        count = 0;
    }

    private static String phaseName(byte phase) {
        return switch (phase) {
            case SEEK -> "SEEK";
            case ALIGN -> "ALIGN";
            default -> "WALK";
        };
    }

    private static void log(String message) {
        try {
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER-DIAG] " + message);
        } catch (RuntimeException ignored) {
        }
    }
}
