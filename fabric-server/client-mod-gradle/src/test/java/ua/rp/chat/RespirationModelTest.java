package ua.rp.chat;

public final class RespirationModelTest {
    private static final double EPSILON = 0.000001;

    public static void main(String[] args) {
        verifyRestingRate();
        verifyPhaseContinuityAcrossAbruptLoad();
        verifyFrameRateIndependence();
        verifyBreathCurveContinuity();
        verifySinglePhaseLockedExhaleEvent();
        verifyRecoveryHysteresis();
        verifyNonFiniteInputsFailSafe();
        System.out.println("RespirationModelTest: all continuity, timing and recovery invariants passed");
    }

    private static void verifyRestingRate() {
        RespirationModel model = new RespirationModel();
        run(model, 30.0, 1.0 / 20.0, RespirationModel.Input.resting());
        RespirationModel.Snapshot snapshot = model.sample(1.0);
        assertClose("resting rate", RespirationModel.RESTING_BPM, snapshot.rateBpm(), 0.001);
        assertBetween("resting phase", snapshot.phase(), 0.0, 1.0);
    }

    private static void verifyPhaseContinuityAcrossAbruptLoad() {
        RespirationModel model = new RespirationModel();
        run(model, 300.0, 1.0 / 20.0, RespirationModel.Input.resting());
        double before = model.sample(1.0).phase();
        model.update(1.0 / 20.0, exhausted());
        double after = model.sample(1.0).phase();
        double advance = wrappedAdvance(before, after);
        double absoluteMaximumAdvance = RespirationModel.MAXIMUM_BPM / 60.0 / 20.0;
        assertTrue("abrupt load cannot jump respiratory phase", advance <= absoluteMaximumAdvance + EPSILON);
        assertTrue("phase still advances after load change", advance > 0.0);
    }

    private static void verifyFrameRateIndependence() {
        RespirationModel thirtyFps = new RespirationModel();
        RespirationModel highFps = new RespirationModel();
        RespirationModel.Input input = new RespirationModel.Input(0.22, 0.68, 0.72, 0.12, 0.18, 0.75, true, false);
        run(thirtyFps, 60.0, 1.0 / 30.0, input);
        run(highFps, 60.0, 1.0 / 240.0, input);
        RespirationModel.Snapshot a = thirtyFps.sample(1.0);
        RespirationModel.Snapshot b = highFps.sample(1.0);
        assertClose("rate is independent of update cadence", a.rateBpm(), b.rateBpm(), 0.0001);
        assertTrue("phase is independent of update cadence", circularDistance(a.phase(), b.phase()) < 0.0025);
        assertClose("intensity is independent of update cadence", a.intensity(), b.intensity(), 0.0001);
    }

    private static void verifyBreathCurveContinuity() {
        double leftInhale = RespirationModel.breathCurve(0.38 - 0.00001);
        double rightInhale = RespirationModel.breathCurve(0.38 + 0.00001);
        double leftExhale = RespirationModel.breathCurve(RespirationModel.EXHALE_START - 0.00001);
        double rightExhale = RespirationModel.breathCurve(RespirationModel.EXHALE_START + 0.00001);
        double leftPause = RespirationModel.breathCurve(0.90 - 0.00001);
        double rightPause = RespirationModel.breathCurve(0.90 + 0.00001);
        assertTrue("inhale-to-hold boundary is continuous", Math.abs(leftInhale - rightInhale) < 0.00001);
        assertTrue("hold-to-exhale boundary is continuous", Math.abs(leftExhale - rightExhale) < 0.00001);
        assertTrue("exhale-to-pause boundary is continuous", Math.abs(leftPause - rightPause) < 0.00001);
        for (int i = 0; i <= 1000; i++) {
            assertBetween("breath curve bounds", RespirationModel.breathCurve(i / 1000.0), 0.0, 1.0);
        }
    }

    private static void verifySinglePhaseLockedExhaleEvent() {
        RespirationModel model = new RespirationModel();
        int events = 0;
        boolean previousEvent = false;
        for (int i = 0; i < 180 * 20; i++) {
            RespirationModel.Snapshot snapshot = model.update(1.0 / 20.0, exhausted());
            boolean event = model.startedExhale();
            if (event) {
                events++;
                assertTrue("exhale event occurs at the exhale boundary",
                        snapshot.phase() >= RespirationModel.EXHALE_START && snapshot.phase() < 0.48);
            }
            assertFalse("exhale event is never repeated on adjacent ticks", event && previousEvent);
            previousEvent = event;
        }
        assertTrue("exhausted breathing stays below the physiological maximum", events <= 150);
        assertTrue("exhausted breathing remains meaningfully audible", events >= 110);
    }

    private static void verifyRecoveryHysteresis() {
        RespirationModel model = new RespirationModel();
        run(model, 12.0, 1.0 / 20.0, exhausted());
        double peakRate = model.sample(1.0).rateBpm();
        assertTrue("exhaustion raises breathing rate", peakRate > 45.0);
        run(model, 1.0, 1.0 / 20.0, RespirationModel.Input.resting());
        RespirationModel.Snapshot earlyRecovery = model.sample(1.0);
        assertTrue("breathing does not snap to rest", earlyRecovery.rateBpm() > 30.0);
        assertTrue("respiratory effort persists during recovery", earlyRecovery.intensity() > 0.45);
        run(model, 35.0, 1.0 / 20.0, RespirationModel.Input.resting());
        RespirationModel.Snapshot recovered = model.sample(1.0);
        assertTrue("rate eventually recovers", recovered.rateBpm() < 14.01);
        assertTrue("effort eventually recovers", recovered.intensity() < 0.001);
    }

    private static void verifyNonFiniteInputsFailSafe() {
        RespirationModel model = new RespirationModel();
        RespirationModel.Input invalid = new RespirationModel.Input(
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NaN, Double.NaN, Double.NaN, false, false);
        RespirationModel.Snapshot snapshot = model.update(Double.NaN, invalid);
        assertTrue("invalid input cannot poison phase", Double.isFinite(snapshot.phase()));
        assertTrue("invalid input cannot poison rate", Double.isFinite(snapshot.rateBpm()));
        assertTrue("invalid input cannot poison intensity", Double.isFinite(snapshot.intensity()));
    }

    private static RespirationModel.Input exhausted() {
        return new RespirationModel.Input(0.0, 1.0, 1.0, 0.2, 0.3, 1.0, true, false);
    }

    private static void run(RespirationModel model, double seconds, double step, RespirationModel.Input input) {
        int iterations = (int) Math.round(seconds / step);
        for (int i = 0; i < iterations; i++) {
            model.update(step, input);
        }
    }

    private static double wrappedAdvance(double from, double to) {
        double value = to - from;
        return value < 0.0 ? value + 1.0 : value;
    }

    private static double circularDistance(double a, double b) {
        double distance = Math.abs(a - b);
        return Math.min(distance, 1.0 - distance);
    }

    private static void assertClose(String name, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertBetween(String name, double value, double min, double max) {
        assertTrue(name + ": expected [" + min + ", " + max + "], got " + value,
                value >= min && value <= max);
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) {
            throw new AssertionError(name);
        }
    }

    private static void assertFalse(String name, boolean value) {
        assertTrue(name, !value);
    }
}
