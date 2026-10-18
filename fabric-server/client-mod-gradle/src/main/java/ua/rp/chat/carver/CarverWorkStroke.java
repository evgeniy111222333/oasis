package ua.rp.chat.carver;

/**
 * Hammer-stroke choreography for one work cycle, phase {@code t} in [0, 1):
 * a slow 65% windup, a fast 25% strike down and a 10% elastic recoil off the
 * contact point. A plain sine moves weightlessly; this one reads as mass
 * hitting stone because nearly all velocity lives in the strike quarter.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverWorkStroke {
    /** Strikes per cycle second feel: one accent per this many work ticks. */
    public static final int TICKS_PER_STRIKE = 25;

    private CarverWorkStroke() {
    }

    /** Hammer lift at phase t: 1 raised, 0 on the chisel. */
    public static double lift(double t) {
        t -= Math.floor(t);
        if (t < 0.65) {
            double u = t / 0.65;
            return u * u * (3.0 - 2.0 * u);
        }
        if (t < 0.9) {
            double u = (t - 0.65) / 0.25;
            return 1.0 - u * u;
        }
        double u = (t - 0.9) / 0.1;
        return Math.sin(u * Math.PI) * 0.18;
    }

    /** Contact pulse at phase t: 1 exactly while the hammer sits on the stone. */
    public static double contact(double t) {
        t -= Math.floor(t);
        if (t < 0.9) return 0.0;
        double u = (t - 0.9) / 0.1;
        return Math.sin(u * Math.PI);
    }

    /**
     * Forward drive toward the stone at phase t: 0 through the whole windup, ramping to 1
     * exactly at impact and decaying across the settle. The torso and knees lean on this,
     * so the body follows through with the swing instead of standing while the arm moves.
     * Pure.
     */
    public static double drive(double t) {
        t -= Math.floor(t);
        if (t < 0.65) return 0.0;
        if (t < 0.9) {
            double u = (t - 0.65) / 0.25;
            return u * u * (3.0 - 2.0 * u);
        }
        double u = (t - 0.9) / 0.1;
        return 1.0 - u * u;
    }

    /**
     * One-sided impact shock at phase t: a sharp, fast-decaying bump right after the contact
     * pulse. Drives the chisel-hand shiver, the torso dip and the head nod, so the whole body
     * absorbs the blow on the impact frame and recovers smoothly. Pure, non-negative.
     */
    public static double shock(double t) {
        t -= Math.floor(t);
        if (t < 0.9) return 0.0;
        double u = (t - 0.9) / 0.1;
        return (1.0 - u) * (1.0 - u);
    }

    /** Whole strike count for a job, at least a few accents even for short work. */
    public static int strikesFor(int totalTicks) {
        return Math.max(3, (int) Math.ceil(totalTicks / (double) TICKS_PER_STRIKE));
    }

    /** Phase of the smooth work clock inside its strike cycle. Pure. */
    public static double cycleOf(double smoothTicks, int totalTicks) {
        if (totalTicks <= 0) return 0.0;
        double cycles = strikesFor(totalTicks);
        double position = Math.min(smoothTicks, (double) totalTicks) / totalTicks * cycles;
        return position - Math.floor(position);
    }

    /** Strike index of the smooth work clock: increments once per impact. Pure. */
    public static int strikeIndex(double smoothTicks, int totalTicks) {
        if (totalTicks <= 0) return 0;
        double cycles = strikesFor(totalTicks);
        return (int) Math.floor(
                Math.min(smoothTicks, (double) totalTicks) / totalTicks * cycles);
    }

    /** Per-strike interval spread of the humanized rhythm, as a fraction of the mean. */
    public static final double RHYTHM_JITTER = 0.14;

    /** One work tick located in the rhythm: which strike, and the phase inside it. */
    public record Placement(int index, double cycle) {
    }

    /**
     * Seeded, humanized placement: every strike gets a slightly different length, so the
     * artisan never falls into a metronome. Bounded, deterministic and pure, so the client
     * and every observer compute the exact same beat from the same seed and work clock.
     */
    public static Placement placement(double tick, int totalTicks, long seed) {
        if (totalTicks <= 0) return new Placement(0, 0.0);
        int strikes = strikesFor(totalTicks);
        double base = totalTicks / (double) strikes;
        double rawSum = 0.0;
        for (int n = 0; n < strikes; n++) rawSum += base * intervalJitter(seed, n);
        double scale = rawSum <= 0.0 ? 1.0 : totalTicks / rawSum;
        double t = Math.max(0.0, Math.min((double) totalTicks, tick));
        double acc = 0.0;
        for (int n = 0; n < strikes; n++) {
            double duration = base * intervalJitter(seed, n) * scale;
            if (t < acc + duration || n == strikes - 1) {
                double cycle = duration <= 0.0 ? 0.0 : (t - acc) / duration;
                // Clamp just below 1 so the phase never reaches the next strike's zero.
                return new Placement(n, Math.max(0.0, Math.min(0.999999, cycle)));
            }
            acc += duration;
        }
        return new Placement(strikes - 1, 0.0);
    }

    /** Humanized phase of the work clock for one artisan seed. Pure. */
    public static double cycleOf(double smoothTicks, int totalTicks, long seed) {
        return placement(smoothTicks, totalTicks, seed).cycle();
    }

    /** Humanized strike index of the work clock for one artisan seed. Pure. */
    public static int strikeIndex(double smoothTicks, int totalTicks, long seed) {
        return placement(smoothTicks, totalTicks, seed).index();
    }

    /**
     * SplitMix64-style deterministic jitter in {@code [1 - RHYTHM_JITTER, 1 + RHYTHM_JITTER]}
     * for one strike of one artisan. Pure: same seed and strike always give the same length.
     */
    private static double intervalJitter(long seed, int strike) {
        long h = seed + 0x9E3779B97F4A7C15L * (strike + 1L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        double unit = (h >>> 11) / (double) (1L << 53);
        return 1.0 + (unit * 2.0 - 1.0) * RHYTHM_JITTER;
    }
}
