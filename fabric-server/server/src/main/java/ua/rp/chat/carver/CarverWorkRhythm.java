package ua.rp.chat.carver;

/**
 * Deterministic rhythm engine for simulated carving: strikes land exactly on arm
 * swings, scrapes sit halfway between them, overlay layers and crack accents fall on
 * fixed counts. Tempo follows material hardness through the same multiplier that
 * prices the work, so soft blocks genuinely patter faster than stone thuds.
 *
 * <p>Pure and dependency-free: the tick-to-slot schedule snapshot-tests without a
 * Minecraft server.</p>
 */
public final class CarverWorkRhythm {
    public enum Slot { NONE, STRIKE, SCRAPE }

    public static final int MIN_SWING_EVERY = 8;
    public static final int MAX_SWING_EVERY = 40;
    /** Every Nth scrape carries the snip overlay. */
    public static final int SNIP_EVERY_SCRAPE = 3;
    /** Every Nth strike carries the strip overlay. */
    public static final int STRIP_EVERY_STRIKE = 4;

    private CarverWorkRhythm() {
    }

    /** Swing period in ticks for the hardness multiplier over the tuned base period. */
    public static int swingEvery(double multiplier, int basePeriod) {
        return Math.min(MAX_SWING_EVERY,
                Math.max(MIN_SWING_EVERY, (int) Math.round(basePeriod * multiplier)));
    }

    /** Which sound slot (if any) a work tick plays. Tick 0 is always a strike. */
    public static Slot slotForTick(int doneTick, int swingEvery) {
        if (swingEvery <= 0) return Slot.NONE;
        if (doneTick % swingEvery == 0) return Slot.STRIKE;
        if (doneTick % swingEvery == swingEvery / 2 && swingEvery / 2 > 0) return Slot.SCRAPE;
        return Slot.NONE;
    }

    /** Strike ordinal among strikes so far (tick 0 is strike 0). */
    public static int strikeIndex(int doneTick, int swingEvery) {
        return doneTick / Math.max(1, swingEvery);
    }

    /** Count of scrape ticks at or before this tick (tick 0 is never a scrape). */
    public static int scrapeIndex(int doneTick, int swingEvery) {
        int period = Math.max(1, swingEvery);
        int half = period / 2;
        if (half <= 0) return 0;
        return doneTick / period + (doneTick % period >= half ? 1 : 0);
    }

    /** Loudness envelope over progress: firm entry, even middle, delicate finish. */
    public static double volumeEnvelope(double progress) {
        if (progress < 0.1) return 1.15;
        if (progress > 0.9) return 0.75;
        return 1.0;
    }

    /** Strike pitch climbs with progress so the ear hears the end approaching. */
    public static float strikePitch(double progress, float jitter) {
        return (float) (0.9 + progress * 0.2 + jitter);
    }

    /** True when progress just crossed a quarter mark (crack accent moments). */
    public static boolean milestoneCrossed(double previous, double current) {
        return crossed(previous, current, 0.25)
                || crossed(previous, current, 0.5)
                || crossed(previous, current, 0.75);
    }

    private static boolean crossed(double previous, double current, double mark) {
        return previous < mark && current >= mark;
    }
}
