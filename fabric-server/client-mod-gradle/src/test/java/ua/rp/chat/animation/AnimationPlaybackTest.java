package ua.rp.chat.animation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Standalone, Minecraft-free regression checks for authored playback. */
public final class AnimationPlaybackTest {
    private static final double EPS = 0.0002;
    private static AnimationLibrary library;

    public static void main(String[] args) throws Exception {
        try (InputStream stream = AnimationPlaybackTest.class.getResourceAsStream(
                "/assets/eclipseclient/animations/rp_character_v3.animation.json")) {
            check("bundled authored resource is present", stream != null);
            library = AnimationLibrary.load(stream);
        }
        interpolationAndSparseChannels();
        allAuthoredClipsHoldOrLoop();
        invalidInputFailsAtLoad();
        directionalMotion();
        startsStopsAndInterruptions();
        crouchHoldsAndExits();
        stationaryTurns();
        speedAndFrameIndependence();
        unsupportedStatesAndTimeReset();
        System.out.println("AnimationPlaybackTest: all 22 clips, interpolation, transitions, directions, "
                + "crouch holds, stationary turns and independent playback passed");
    }

    private static void interpolationAndSparseChannels() throws Exception {
        AnimationLibrary fixture = load("""
                {"animations":{"test":{"loop":"hold_on_last_frame","animation_length":3,
                "bones":{"curve":{"rotation":{"0":{"post":[0,0,0],"lerp_mode":"catmullrom"},
                "1":{"post":[0,0,0],"lerp_mode":"catmullrom"},
                "2":{"post":[10,0,0],"lerp_mode":"catmullrom"},
                "3":{"post":[10,0,0],"lerp_mode":"catmullrom"}}},
                "line":{"position":{"0":[0,0,0],"1":[20,0,0]}}}},
                "other":{"loop":true,"animation_length":1,"bones":{"line":{"rotation":[1,2,3]}}}}}
                """);
        close("Catmull-Rom bends between the middle controls", 2.03125,
                fixture.sample("test", 1.25).rotation("curve").x());
        close("linear tracks interpolate numerically", 5,
                fixture.sample("test", .25).position("line").x());
        close("unkeyed position resets", 0, fixture.sample("other", .2).position("line").x());
        close("bone omitted by next clip resets", 0, fixture.sample("other", .2).rotation("curve").x());
        close("unknown bone is identity", 0, fixture.sample("test", .2).rotation("absent").x());
        close("source degrees are retained", 3, fixture.sample("other", .2).rotation("line").z());
        close("negative time clamps to first key", 0, fixture.sample("test", -2).rotation("curve").x());
        close("held final value remains stable", 10, fixture.sample("test", 20).rotation("curve").x());
    }

    private static void allAuthoredClipsHoldOrLoop() {
        check("all authored clips load", library.clipCount() == 22);
        check("all authored bones load", library.boneNames().size() == 22);
        Set<String> required = Set.of("idle", "walk_start", "walk_loop", "walk_stop", "sneak_enter",
                "sneak_loop", "sneak_exit", "sprint_start", "sprint_loop", "sprint_stop",
                "walk_backward_start", "walk_backward_loop", "walk_backward_stop",
                "walk_strafe_left_loop", "walk_strafe_right_loop", "walk_diag_fl_loop", "walk_diag_fr_loop",
                "sneak_backward_loop", "sneak_strafe_left_loop", "sneak_strafe_right_loop",
                "turn_left_90", "turn_right_90");
        check("every supplied name is addressable", library.clipNames().equals(required));
        int loops = 0;
        for (String name : required) {
            double length = library.length(name);
            AnimationPose first = library.sample(name, 0);
            AnimationPose last = library.sample(name, length);
            poseClose("full names resolve " + name, first,
                    library.sample("animation.rp_humanoid." + name, 0), EPS);
            if (library.loops(name)) {
                loops++;
                poseClose("loop closes " + name, first, last, EPS);
                poseClose("loop wraps " + name, library.sample(name, .031),
                        library.sample(name, length + .031), EPS);
                poseClose("loop has no visible endpoint jump " + name,
                        library.sample(name, length - .000001), first, .002);
            } else {
                poseClose("one-shot holds endpoint " + name, last, library.sample(name, length + 100), EPS);
            }
            for (double time : new double[]{0, length * .17, length * .53, length * .91, length}) {
                AnimationPose pose = library.sample(name, time);
                for (String bone : library.boneNames()) {
                    finite(name + " rotation " + bone, pose.rotation(bone));
                    finite(name + " position " + bone, pose.position(bone));
                }
            }
        }
        check("12 authored loops are recognized", loops == 12);
        close("walk start uses source pixels", -.15, library.sample("walk_start", 0).position("center_of_mass").y());
        close("walk center-of-mass key is preserved", -.95,
                library.sample("walk_loop", .15).position("center_of_mass").y());
        close("toe rotation reaches authored key", -8, library.sample("walk_loop", .3).rotation("left_toe").x());
        close("hand rotation reaches authored key", 12, library.sample("walk_loop", .2).rotation("left_hand").x());
    }

    private static void invalidInputFailsAtLoad() throws Exception {
        expectFailure("Molang is rejected instead of silently flattening animation", () -> load("""
                {"animations":{"bad":{"animation_length":1,"bones":{"head":{"rotation":["query.life_time",0,0]}}}}}
                """));
        expectFailure("invalid clip length is rejected", () -> load("""
                {"animations":{"bad":{"animation_length":0,"bones":{}}}}
                """));
        expectFailure("unknown clip is explicit", () -> library.sample("missing", 0));
        expectFailure("non-finite playback time is rejected", () -> library.sample("idle", Double.NaN));
    }

    private static void directionalMotion() {
        clipAfter("walk_loop", motion(4.3, 1, 0, false, false, 0));
        clipAfter("walk_backward_loop", motion(4.3, -1, 0, true, false, 0));
        clipAfter("walk_strafe_left_loop", motion(4.3, 0, 1, false, false, 0));
        clipAfter("walk_strafe_right_loop", motion(4.3, 0, -1, false, false, 0));
        clipAfter("walk_diag_fl_loop", motion(4.3, .707, .707, false, false, 0));
        clipAfter("walk_diag_fr_loop", motion(4.3, .707, -.707, false, false, 0));
        clipAfter("sprint_loop", motion(5.6, 1, 0, true, false, 0));
        clipAfter("sneak_loop", motion(1.3, 1, 0, false, true, 0));
        clipAfter("sneak_backward_loop", motion(1.3, -1, 0, false, true, 0));
        clipAfter("sneak_strafe_left_loop", motion(1.3, 0, 1, false, true, 0));
        clipAfter("sneak_strafe_right_loop", motion(1.3, 0, -1, false, true, 0));
    }

    private static void startsStopsAndInterruptions() {
        LocomotionController c = controller();
        LocomotionController.Motion walk = motion(4.3, 1, 0, false, false, 0);
        equal("walking starts with authored start", "walk_start", c.sample(0, walk).clip());
        equal("start crosses into loop", "walk_loop", c.sample(.6, walk).clip());
        AnimationPose before = c.sample(.71, walk).pose();
        LocomotionController.Motion strafe = motion(4.3, 0, -1, false, false, 0);
        LocomotionController.Frame changed = c.sample(.71, strafe);
        equal("direction switch selects right strafe", "walk_strafe_right_loop", changed.clip());
        poseClose("direction switch begins at actual sampled pose", before, changed.pose(), EPS);
        c.sample(.92, strafe);
        close("channels omitted by directional clips reset", 0, c.sample(.92, strafe).pose().rotation("left_toe").x());
        close("hand omitted by directional clips resets", 0, c.sample(.92, strafe).pose().rotation("left_hand").x());
        equal("directional walking uses authored stop", "walk_stop", c.sample(1, stationary(false, 0)).clip());
        equal("walking stop settles to idle", "idle", c.sample(1.8, stationary(false, 0)).clip());

        c = controller();
        c.sample(0, walk);
        AnimationPose start = c.sample(.19, walk).pose();
        LocomotionController.Frame stop = c.sample(.19, stationary(false, 0));
        equal("start can be interrupted by stop", "walk_stop", stop.clip());
        poseClose("interrupted start remains continuous", start, stop.pose(), EPS);
        AnimationPose stopping = c.sample(.27, stationary(false, 0)).pose();
        LocomotionController.Frame resumed = c.sample(.27, walk);
        equal("stop can be interrupted by restart", "walk_start", resumed.clip());
        poseClose("interrupted stop remains continuous", stopping, resumed.pose(), EPS);

        c = controller();
        LocomotionController.Motion sprint = motion(5.6, 1, 0, true, false, 0);
        equal("sprint start is selected", "sprint_start", c.sample(0, sprint).clip());
        c.sample(.5, sprint);
        equal("sprint stop is selected", "sprint_stop", c.sample(.7, stationary(false, 0)).clip());
        c = controller();
        LocomotionController.Motion backward = motion(4.3, -1, 0, false, false, 0);
        equal("backward start is selected", "walk_backward_start", c.sample(0, backward).clip());
        c.sample(.6, backward);
        equal("backward stop is selected", "walk_backward_stop", c.sample(.7, stationary(false, 0)).clip());
    }

    private static void crouchHoldsAndExits() {
        LocomotionController c = controller();
        equal("crouch enters through authored one-shot", "sneak_enter", c.sample(0, stationary(true, 0)).clip());
        AnimationPose held = c.sample(.8, stationary(true, 0)).pose();
        poseClose("stationary crouch holds enter endpoint", library.sample("sneak_enter", .45), held, EPS);
        poseClose("stationary crouch does not march", held, c.sample(4, stationary(true, 0)).pose(), EPS);
        LocomotionController.Motion sneak = motion(1.3, -1, 0, false, true, 0);
        equal("crouch hold enters backward movement", "sneak_backward_loop", c.sample(4, sneak).clip());
        poseClose("crouch movement begins continuously", held, c.sample(4, sneak).pose(), EPS);
        c.sample(4.3, sneak);
        c.sample(4.3, stationary(true, 0));
        poseClose("crouch stop returns to stationary stance", library.sample("sneak_enter", .45),
                c.sample(4.6, stationary(true, 0)).pose(), EPS);
        equal("standing uses authored crouch exit", "sneak_exit", c.sample(4.6, stationary(false, 0)).clip());
        equal("exit settles in idle", "idle", c.sample(5.2, stationary(false, 0)).clip());
        c = controller();
        c.sample(0, stationary(true, 0));
        c.sample(.2, stationary(false, 0));
        AnimationPose leaving = c.sample(.3, stationary(false, 0)).pose();
        poseClose("interrupted exit can re-enter continuously", leaving,
                c.sample(.3, stationary(true, 0)).pose(), EPS);
    }

    private static void stationaryTurns() {
        LocomotionController c = controller();
        c.sample(0, stationary(false, 0));
        equal("small yaw jitter stays idle", "idle", c.sample(.1, stationary(false, 8)).clip());
        equal("actual positive body yaw triggers right turn", "turn_right_90", c.sample(.2, stationary(false, 65)).clip());
        equal("completed stationary turn returns to idle", "idle", c.sample(1, stationary(false, 65)).clip());
        equal("unchanged facing cannot retrigger turn", "idle", c.sample(2, stationary(false, 65)).clip());
        equal("actual negative body yaw triggers left turn", "turn_left_90", c.sample(2.1, stationary(false, -5)).clip());
        c = controller();
        c.sample(0, stationary(false, 170));
        equal("yaw wrapping does not create a false 340 degree turn", "idle", c.sample(.1, stationary(false, -170)).clip());
        c = controller();
        c.sample(0, stationary(true, 0));
        equal("crouch keeps its stance while facing changes", "sneak_enter", c.sample(.6, stationary(true, 90)).clip());
    }

    private static void speedAndFrameIndependence() {
        LocomotionController fast = controller();
        LocomotionController slow = controller();
        LocomotionController.Motion walk = motion(4.3, 1, 0, false, false, 0);
        LocomotionController.Motion half = motion(2.15, 1, 0, false, false, 0);
        fast.sample(0, walk);
        slow.sample(0, half);
        fast.sample(.55, walk);
        slow.sample(.55, half);
        poseClose("normal walking advances authored phase at source rate", library.sample("walk_loop", .3),
                fast.sample(.85, walk).pose(), EPS);
        poseClose("half-speed walking advances phase at half rate", library.sample("walk_loop", .15),
                slow.sample(.85, half).pose(), EPS);
        LocomotionController low = controller();
        LocomotionController.Motion creeping = motion(.1, 1, 0, false, false, 0);
        low.sample(0, creeping);
        equal("low-speed starts finish in real time", "walk_loop", low.sample(.6, creeping).clip());

        LocomotionController fps20 = controller();
        LocomotionController fps120 = controller();
        playSchedule(fps20, 20);
        playSchedule(fps120, 120);
        poseClose("frame count cannot change pose at equal time", fps20.sample(2, stationary(false, 0)).pose(),
                fps120.sample(2, stationary(false, 0)).pose(), EPS);
        LocomotionController independent = controller();
        independent.sample(0, stationary(true, 0));
        fast.sample(1, walk);
        poseClose("another entity cannot advance held crouch", library.sample("sneak_enter", .45),
                independent.sample(1, stationary(true, 0)).pose(), EPS);
        LocomotionController sparse = controller();
        sparse.sample(0, walk);
        poseClose("a long rendering gap carries start overshoot into loop phase", library.sample("walk_loop", .5),
                sparse.sample(1.05, walk).pose(), EPS);
        LocomotionController sprint = controller();
        LocomotionController.Motion sprintMotion = motion(2.8, 1, 0, true, false, 0);
        sprint.sample(0, sprintMotion);
        poseClose("sprint phase uses its 5.6 blocks-per-second reference", library.sample("sprint_loop", .2),
                sprint.sample(.85, sprintMotion).pose(), EPS);
        LocomotionController crouch = controller();
        LocomotionController.Motion crouchMotion = motion(.65, 1, 0, false, true, 0);
        crouch.sample(0, crouchMotion);
        poseClose("crouch phase uses its 1.3 blocks-per-second reference", library.sample("sneak_loop", .3),
                crouch.sample(1.05, crouchMotion).pose(), EPS);
    }

    private static void unsupportedStatesAndTimeReset() {
        LocomotionController c = controller();
        LocomotionController.Motion walk = motion(4.3, 1, 0, false, false, 0);
        c.sample(10, walk);
        c.sample(11, walk);
        LocomotionController.Frame rewound = c.sample(0, walk);
        equal("entity age rewind restarts locomotion", "walk_start", rewound.clip());
        LocomotionController.Frame unsupported = c.sample(.1,
                new LocomotionController.Motion(4.3, 1, 0, false, false, false, 0));
        check("unsupported airborne state returns inactive", !unsupported.active());
        for (String bone : library.boneNames()) {
            vectorClose("inactive rotation is identity", new AnimationPose.Vector(0, 0, 0), unsupported.pose().rotation(bone), EPS);
            vectorClose("inactive position is identity", new AnimationPose.Vector(0, 0, 0), unsupported.pose().position(bone), EPS);
        }
        equal("landing restarts a fresh start", "walk_start", c.sample(.2, walk).clip());
    }

    private static void playSchedule(LocomotionController c, int fps) {
        for (int i = 0; i <= fps * 2; i++) {
            double time = i / (double) fps;
            c.sample(time, time < 1 ? motion(4.3, 1, 0, false, false, 0) : stationary(false, 0));
        }
    }

    private static void clipAfter(String expected, LocomotionController.Motion motion) {
        LocomotionController c = controller();
        c.sample(0, motion);
        equal("direction selects " + expected, expected, c.sample(1, motion).clip());
    }

    private static LocomotionController controller() { return new LocomotionController(library); }
    private static LocomotionController.Motion stationary(boolean crouch, float yaw) {
        return motion(0, 0, 0, false, crouch, yaw);
    }
    private static LocomotionController.Motion motion(double speed, double forward, double left,
                                                     boolean sprint, boolean crouch, float yaw) {
        return new LocomotionController.Motion(speed, forward, left, sprint, crouch, true, yaw);
    }
    private static AnimationLibrary load(String json) throws Exception {
        return AnimationLibrary.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    private static void poseClose(String name, AnimationPose expected, AnimationPose actual, double tolerance) {
        for (String bone : library.boneNames()) {
            vectorClose(name + " rotation " + bone, expected.rotation(bone), actual.rotation(bone), tolerance);
            vectorClose(name + " position " + bone, expected.position(bone), actual.position(bone), tolerance);
        }
    }
    private static void vectorClose(String name, AnimationPose.Vector expected, AnimationPose.Vector actual, double tolerance) {
        close(name + " x", expected.x(), actual.x(), tolerance);
        close(name + " y", expected.y(), actual.y(), tolerance);
        close(name + " z", expected.z(), actual.z(), tolerance);
    }
    private static void finite(String name, AnimationPose.Vector vector) {
        check(name, Float.isFinite(vector.x()) && Float.isFinite(vector.y()) && Float.isFinite(vector.z()));
    }
    private static void close(String name, double expected, double actual) { close(name, expected, actual, EPS); }
    private static void close(String name, double expected, double actual, double tolerance) {
        check(name + ": expected " + expected + ", got " + actual, Math.abs(expected - actual) <= tolerance);
    }
    private static void equal(String name, String expected, String actual) { check(name + ": " + actual, expected.equals(actual)); }
    private static void check(String name, boolean condition) { if (!condition) throw new AssertionError(name); }
    private static void expectFailure(String name, CheckedRunnable action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | java.io.IOException expected) { return; }
        throw new AssertionError(name);
    }
    private interface CheckedRunnable { void run() throws Exception; }
}
