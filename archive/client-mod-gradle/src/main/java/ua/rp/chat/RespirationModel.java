package ua.rp.chat;

/**
 * Frame-rate independent respiratory oscillator shared by animation, camera,
 * sound and screen effects. The phase is integrated over time, so changing the
 * requested breathing rate can never jump to another point in the cycle.
 */
public final class RespirationModel {
    public static final double RESTING_BPM = 14.0;
    public static final double MAXIMUM_BPM = 50.0;
    public static final double EXHALE_START = 0.43;

    private double previousPhase;
    private double phase;
    private double previousIntensity;
    private double intensity;
    private double previousRateBpm = RESTING_BPM;
    private double rateBpm = RESTING_BPM;
    private boolean startedExhale;

    public void reset() {
        previousPhase = 0.0;
        phase = 0.0;
        previousIntensity = 0.0;
        intensity = 0.0;
        previousRateBpm = RESTING_BPM;
        rateBpm = RESTING_BPM;
        startedExhale = false;
    }

    public Snapshot update(double deltaSeconds, Input input) {
        double dt = finiteClamp(deltaSeconds, 0.0, 0.25, 0.0);
        Input safeInput = input == null ? Input.resting() : input;

        previousPhase = phase;
        previousIntensity = intensity;
        previousRateBpm = rateBpm;

        double targetIntensity = targetIntensity(safeInput);
        double intensityTau = targetIntensity > intensity ? 0.65 : 2.80;
        intensity = smoothSignal(intensity, targetIntensity, dt, intensityTau);

        double targetRate = rateForIntensity(targetIntensity);
        double rateTau = targetRate > rateBpm ? 0.90 : 3.50;
        rateBpm = smoothSignal(rateBpm, targetRate, dt, rateTau);
        rateBpm = clamp(rateBpm, RESTING_BPM, MAXIMUM_BPM);

        double advance = ((previousRateBpm + rateBpm) * 0.5 / 60.0) * dt;
        double nextPhase = previousPhase + advance;
        startedExhale = crossedBoundary(previousPhase, nextPhase, EXHALE_START);
        phase = wrap01(nextPhase);
        return sample(1.0);
    }

    public Snapshot sample(double partialTick) {
        double alpha = finiteClamp(partialTick, 0.0, 1.0, 1.0);
        double phaseAdvance = phase - previousPhase;
        if (phaseAdvance < 0.0) {
            phaseAdvance += 1.0;
        }
        double sampledPhase = wrap01(previousPhase + phaseAdvance * alpha);
        double sampledIntensity = lerp(previousIntensity, intensity, alpha);
        double sampledRate = lerp(previousRateBpm, rateBpm, alpha);
        return snapshotForPhase(sampledPhase, sampledRate, sampledIntensity);
    }

    public boolean startedExhale() {
        return startedExhale;
    }

    public static Snapshot snapshotForPhase(double phase, double rateBpm, double intensity) {
        double safePhase = wrap01(phase);
        double expansion = breathCurve(safePhase);
        return new Snapshot(
                safePhase,
                expansion,
                clamp(rateBpm, RESTING_BPM, MAXIMUM_BPM),
                clamp(intensity, 0.0, 1.0));
    }

    public static double breathCurve(double phase) {
        double p = wrap01(phase);
        if (p < 0.38) {
            return smootherStep(p / 0.38);
        }
        if (p < EXHALE_START) {
            return 1.0;
        }
        if (p < 0.90) {
            return 1.0 - smootherStep((p - EXHALE_START) / (0.90 - EXHALE_START));
        }
        return 0.0;
    }

    public static double smoothSignal(double current, double target, double deltaSeconds, double timeConstant) {
        double dt = finiteClamp(deltaSeconds, 0.0, 1.0, 0.0);
        double tau = Math.max(0.001, timeConstant);
        double alpha = 1.0 - Math.exp(-dt / tau);
        return current + (target - current) * alpha;
    }

    private static double targetIntensity(Input input) {
        double lowStamina = 1.0 - input.stamina01();
        double locomotion = input.movement01() * (input.sprinting() ? 1.0 : 0.72);
        double metabolic = Math.max(locomotion,
                Math.max(lowStamina * 0.84,
                        Math.max(input.breathDebt01() * 0.94, input.fatigue01() * 0.70)));
        double medical = input.bloodLoss01() * 0.42 + input.pain01() * 0.12;
        double load = input.unconscious() ? 1.0 : Math.max(metabolic, medical);
        return smootherStep(clamp((load - 0.04) / 0.91, 0.0, 1.0));
    }

    private static double rateForIntensity(double intensity) {
        double normal = smootherStep(clamp((intensity - 0.04) / 0.84, 0.0, 1.0));
        double critical = smootherStep(clamp((intensity - 0.78) / 0.22, 0.0, 1.0));
        return RESTING_BPM + normal * 30.0 + critical * 6.0;
    }

    private static boolean crossedBoundary(double previous, double current, double boundary) {
        return Math.floor(previous - boundary) < Math.floor(current - boundary);
    }

    private static double smootherStep(double value) {
        double x = clamp(value, 0.0, 1.0);
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double from, double to, double alpha) {
        return from + (to - from) * alpha;
    }

    private static double wrap01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return value - Math.floor(value);
    }

    private static double finiteClamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? clamp(value, min, max) : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Input(
            double stamina01,
            double breathDebt01,
            double fatigue01,
            double bloodLoss01,
            double pain01,
            double movement01,
            boolean sprinting,
            boolean unconscious) {
        public Input {
            stamina01 = finiteClamp(stamina01, 0.0, 1.0, 1.0);
            breathDebt01 = finiteClamp(breathDebt01, 0.0, 1.0, 0.0);
            fatigue01 = finiteClamp(fatigue01, 0.0, 1.0, 0.0);
            bloodLoss01 = finiteClamp(bloodLoss01, 0.0, 1.0, 0.0);
            pain01 = finiteClamp(pain01, 0.0, 1.0, 0.0);
            movement01 = finiteClamp(movement01, 0.0, 1.0, 0.0);
        }

        public static Input resting() {
            return new Input(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, false);
        }
    }

    public record Snapshot(double phase, double expansion, double rateBpm, double intensity) {
    }
}
