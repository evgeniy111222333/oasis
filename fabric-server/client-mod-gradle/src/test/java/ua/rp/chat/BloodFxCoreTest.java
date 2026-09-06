package ua.rp.chat;

import ua.rp.chat.blood.BloodFxRules;
import ua.rp.chat.blood.BloodSkinUv;
import ua.rp.chat.blood.BloodVolumeRules;
import ua.rp.chat.blood.FootprintRules;

public final class BloodFxCoreTest {
    public static void main(String[] args) {
        deterministicEntropy();
        boundedImpactBursts();
        distanceLodIsMonotonic();
        continuingBleedIsBounded();
        irregularCadenceStaysSafe();
        surfaceDryingIsStable();
        decalLibraryIsDeterministicAndTiered();
        decalStagesAdvanceMonotonically();
        skinUvNeverEscapesPartFaces();
        footprintReservoirIsFinite();
        footprintPickupAndDryingArePhysical();
        footprintAtlasAndLifetimeAreBounded();
        normalWalkingAlwaysProducesContacts();
        impactVelocityIsBounded();
        volumeBudgetIsConserved();
        woundProfilesHavePhysicalRatios();
        wallFlowCannotCreateBlood();
        System.out.println("BloodFxCoreTest: deterministic volume, LOD, surface flow and drying invariants passed");
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

    private static void decalLibraryIsDeterministicAndTiered() {
        float[] energies = {0.12f, 0.52f, 0.91f};
        for (int tier = 0; tier < energies.length; tier++) {
            boolean[] seen = new boolean[BloodFxRules.DECAL_VARIANTS_PER_ENERGY];
            for (long seed = 0; seed < 1024; seed++) {
                int family = BloodFxRules.decalFamily(energies[tier], (int) (seed % 6), seed);
                int lower = tier * BloodFxRules.DECAL_VARIANTS_PER_ENERGY;
                int upper = lower + BloodFxRules.DECAL_VARIANTS_PER_ENERGY;
                check(family >= lower && family < upper,
                        "decal energy escaped its authored family");
                check(family == BloodFxRules.decalFamily(
                                energies[tier], (int) (seed % 6), seed),
                        "decal family selection is not deterministic");
                seen[family - lower] = true;
            }
            for (boolean reached : seen) {
                check(reached, "an authored decal variant can never be selected");
            }
        }
        check(BloodFxRules.DECAL_SPRITE_COUNT == 96,
                "runtime decal atlas no longer matches 24 families x 4 stages");
    }

    private static void decalStagesAdvanceMonotonically() {
        int previous = 0;
        boolean[] seen = new boolean[BloodFxRules.DECAL_STAGE_COUNT];
        for (int age = 0; age <= 1000; age++) {
            int stage = BloodFxRules.decalStage(age, 1000);
            check(stage >= previous && stage < BloodFxRules.DECAL_STAGE_COUNT,
                    "decal drying stage moved backwards or escaped its atlas");
            seen[stage] = true;
            previous = stage;
        }
        for (boolean reached : seen) {
            check(reached, "a decal drying stage can never be reached");
        }
    }

    private static void skinUvNeverEscapesPartFaces() {
        for (int zone = 0; zone < 6; zone++) {
            for (int face = 0; face < 4; face++) {
                for (float side : new float[]{-1.0f, 0.0f, 1.0f}) {
                    for (float height : new float[]{0.0f, 0.5f, 1.0f}) {
                        var points = BloodSkinUv.points(zone, face, side, height);
                        check(points.size() == 2, "base/overlay UV pair is missing");
                        for (var point : points) {
                            check(point.x() >= point.face().x()
                                            && point.x() < point.face().x() + point.face().width(),
                                    "wound U escaped its body face");
                            check(point.y() >= point.face().y()
                                            && point.y() < point.face().y() + point.face().height(),
                                    "wound V escaped its body face");
                        }
                    }
                }
            }
        }
    }

    private static void footprintReservoirIsFinite() {
        float wetness = 1.0f;
        int steps = 0;
        while (wetness > 0.0f && steps++ < 20) {
            float deposited = FootprintRules.deposit(
                    wetness, FootprintRules.GAIT_WALK, 0);
            wetness = FootprintRules.afterDeposit(
                    wetness, deposited, FootprintRules.GAIT_WALK);
        }
        check(steps >= 3 && steps <= 7 && wetness == 0.0f,
                "footprint trail does not consume a finite stain reservoir");
    }

    private static void footprintPickupAndDryingArePhysical() {
        float first = FootprintRules.pickup(0.0f, 0.8f, 0.6f, 0, 0);
        float saturated = FootprintRules.pickup(first, 0.8f, 0.6f, 0, 0);
        check(first > 0.0f && saturated > first && saturated <= 1.0f,
                "standing contact does not progressively saturate a sole");
        float clearWeather = FootprintRules.passiveDry(0.8f, false, false);
        float rain = FootprintRules.passiveDry(0.8f, true, false);
        float water = FootprintRules.passiveDry(0.8f, false, true);
        check(clearWeather > rain && rain > water,
                "water and rain do not accelerate sole cleaning");
        check(FootprintRules.deposit(0.8f, FootprintRules.GAIT_RUN, 0)
                        > FootprintRules.deposit(0.8f, FootprintRules.GAIT_CROUCH, 0),
                "running does not transfer more blood than crouch-walking");
    }

    private static void footprintAtlasAndLifetimeAreBounded() {
        check(FootprintRules.SPRITE_COUNT == 48,
                "footprint atlas no longer matches 2 feet x 6 variants x 4 stages");
        for (int foot = 0; foot <= 1; foot++) {
            for (int gait = 0; gait <= FootprintRules.GAIT_SLIDE; gait++) {
                for (long seed = 0; seed < 128; seed++) {
                    int family = FootprintRules.variant(foot, gait, 0, seed);
                    int lower = foot * FootprintRules.VARIANTS_PER_FOOT;
                    check(family >= lower && family < lower + FootprintRules.VARIANTS_PER_FOOT,
                            "left/right footprint family crossed feet");
                    int lifetime = FootprintRules.lifetimeTicks((int) (seed % 6), 0.7f, seed);
                    check(lifetime >= 12_000 && lifetime <= 70_000,
                            "footprint persistence escaped its bounded long-lived range");
                }
            }
        }
        int previous = 0;
        for (int age = 0; age <= 54_000; age += 100) {
            int stage = FootprintRules.stage(1.0f, age, 54_000);
            check(stage >= previous && stage < FootprintRules.STAGES,
                    "footprint drying stage moved backwards");
            previous = stage;
        }
    }

    private static void normalWalkingAlwaysProducesContacts() {
        double travel = 0.0;
        int contacts = 0;
        int nextFoot = FootprintRules.LEFT;
        int left = 0;
        int right = 0;
        for (int tick = 0; tick < 80; tick++) {
            travel = FootprintRules.accumulateTravel(travel, 0.10);
            if (!FootprintRules.contactDue(travel, FootprintRules.GAIT_WALK, 0.36f)) continue;
            if (nextFoot == FootprintRules.LEFT) left++; else right++;
            nextFoot = 1 - nextFoot;
            contacts++;
            travel = FootprintRules.afterContact(travel, FootprintRules.GAIT_WALK, 0.36f);
        }
        check(contacts >= 16 && Math.abs(left - right) <= 1,
                "ordinary walking fails to produce alternating foot contacts");
    }

    private static void impactVelocityIsBounded() {
        for (int profile = 0; profile <= 4; profile++) {
            for (int i = 0; i <= 10; i++) {
                float speed = BloodFxRules.impactSpeed(i / 10.0f, profile, i * 91L);
                check(speed >= 0.015f && speed <= 0.13f,
                        "impact speed can launch implausible litres behind a victim");
            }
        }
    }

    private static void volumeBudgetIsConserved() {
        float accumulator = 0.0f;
        float spent = 0.0f;
        float flow = 2.0f;
        for (int tick = 0; tick < 200; tick++) {
            accumulator = BloodVolumeRules.accumulatorAfterTick(accumulator, flow);
            int drops = BloodVolumeRules.spendableDrops(accumulator);
            float spend = drops * BloodVolumeRules.NOMINAL_DROP_ML;
            accumulator -= spend;
            spent += spend;
        }
        check(Math.abs((spent + accumulator) - 20.0f) < 0.01f,
                "visual scheduler created or destroyed liquid volume");
        check(spent / BloodVolumeRules.NOMINAL_DROP_ML <= 16.01f,
                "a moderate wound produced an implausible particle storm");
    }

    private static void woundProfilesHavePhysicalRatios() {
        float open = BloodVolumeRules.flowRateMlPerSecond(
                20.0f, true, false, false, false, 0.0f);
        float embedded = BloodVolumeRules.flowRateMlPerSecond(
                20.0f, true, false, false, true, 0.0f);
        float moving = BloodVolumeRules.flowRateMlPerSecond(
                20.0f, true, false, false, false, 1.0f);
        check(open > 0.0f && embedded < open * 0.35f,
                "embedded projectile no longer tamponades its wound");
        check(moving > open && moving <= open * 1.36f,
                "movement multiplier became explosive");
        check(BloodVolumeRules.impactVolumeMl(4, 20.0f, 1.0f, false) == 0.0f,
                "fall damage generated external blood");
        check(BloodVolumeRules.impactVolumeMl(3, 20.0f, 1.0f, false) == 0.0f,
                "burn damage generated a fresh blood splash");
    }

    private static void wallFlowCannotCreateBlood() {
        for (float volume = 0.0f; volume <= 40.0f; volume += 0.1f) {
            float transfer = BloodVolumeRules.wallFlowTransfer(volume);
            check(transfer >= 0.0f && transfer <= volume,
                    "vertical surface transfer created liquid");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
