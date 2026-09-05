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
}
