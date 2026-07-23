package ua.rp.chat;

import ua.rp.chat.blood.BloodFxRules;

public final class BloodFxCoreTest {
    public static void main(String[] args) {
        deterministicEntropy();
        boundedImpactBursts();
        distanceLodIsMonotonic();
        continuingBleedIsBounded();
        irregularCadenceStaysSafe();
        surfaceDryingIsStable();
        System.out.println("BloodFxCoreTest: deterministic LOD, cadence, budgets and drying invariants passed");
    }

    private static void deterministicEntropy() {
        long seed = 0x526f6c65706c6179L;
        check(BloodFxRules.mix64(seed) == BloodFxRules.mix64(seed), "seed mixing is not deterministic");
        float unit = BloodFxRules.unitFloat(seed);
        check(unit >= 0.0f && unit < 1.0f, "unit entropy left [0,1)");
    }

    private static void boundedImpactBursts() {
        for (int profile = 0; profile <= 4; profile++) {
            for (int i = -4; i <= 14; i++) {
                int count = BloodFxRules.impactDropCount(i / 10.0f, profile, 4.0);
                check(count >= 0 && count <= BloodFxRules.MAX_IMPACT_DROPS_PER_EVENT,
                        "impact burst exceeded its hard budget");
            }
        }
        check(BloodFxRules.impactDropCount(1.0f, 2, 4.0) <= 1,
                "blunt damage must not create a blood cloud");
    }

    private static void distanceLodIsMonotonic() {
        int near = BloodFxRules.impactDropCount(0.9f, 0, 4.0);
        int medium = BloodFxRules.impactDropCount(0.9f, 0, 24.0 * 24.0);
        int far = BloodFxRules.impactDropCount(0.9f, 0, 48.0 * 48.0);
        int culled = BloodFxRules.impactDropCount(0.9f, 0, 80.0 * 80.0);
        check(near >= medium && medium >= far && far >= culled, "distance LOD is not monotonic");
        check(culled == 0, "effects outside the tracking radius were not culled");
    }

    private static void continuingBleedIsBounded() {
        for (int bleeding = 0; bleeding <= 100; bleeding++) {
            int count = BloodFxRules.continuingDropCount(bleeding, 1.0f, 2.0);
            check(count >= 0 && count <= 3, "continuing emitter exceeded three drops");
        }
        check(BloodFxRules.continuingDropCount(0.0f, 0.0f, 1.0) == 0,
                "closed wound still emits");
    }

    private static void irregularCadenceStaysSafe() {
        boolean varied = false;
        int first = BloodFxRules.emissionIntervalTicks(12.0f, 0.5f, 1L);
        for (long seed = 1; seed < 80; seed++) {
            int interval = BloodFxRules.emissionIntervalTicks(12.0f, 0.5f, seed);
            check(interval >= 5 && interval <= 42, "emission cadence escaped safe bounds");
            varied |= interval != first;
        }
        check(varied, "bleeding cadence became a mechanical fixed metronome");
    }

    private static void surfaceDryingIsStable() {
        for (int material = 0; material <= 5; material++) {
            int lifetime = BloodFxRules.decalLifetimeTicks(material, 0.7f, 91L + material);
            check(lifetime >= 260, "surface decal disappears too quickly");
            float fresh = BloodFxRules.driedColorFactor(0, lifetime);
            float dry = BloodFxRules.driedColorFactor(lifetime, lifetime);
            check(fresh > dry && dry >= 0.50f, "drying color curve is invalid");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
