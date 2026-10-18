package ua.rp.chat.animation;

import java.io.InputStream;

/** Real authored-clip regressions for release inertia and the stop blend. */
public final class LocomotionStopTest {
    private static AnimationLibrary library;

    public static void main(String[] args) throws Exception {
        try (InputStream stream = LocomotionStopTest.class.getResourceAsStream(
                "/assets/eclipseclient/animations/rp_character_v3.animation.json")) {
            library = AnimationLibrary.load(stream);
        }
        remoteDecelerationStartsBeforeInertiaExpires();
        localReleaseIsImmediateAndLatched();
        turningDuringRemoteInertiaCannotRestart();
        steadyLowerSpeedsAndTurnsKeepWalking();
        stableSlowedRemoteMovementRecovers();
        frictionStopStaysStoppedThroughIdle();
        zeroSpeedStopUsesRealTime();
        everyLoopKeepsItsPhaseAcrossFrameRates();
        distancePhaseControlsOnlyMovingLoops();
        releaseRemainsContinuousAtDifferentGaitPhases();
        crouchInputReleaseHoldsItsAuthoredStance();
        suddenStopKeepsMovingDuringItsBlend();
        System.out.println("LocomotionStopTest: local/remote stops, zero-speed motion, interruptions, "
                + "11 directions, crouch holds and distance phase passed at 30/60/144 fps");
    }

    private static void releaseRemainsContinuousAtDifferentGaitPhases() {
        for (boolean sprinting : new boolean[]{false, true}) {
            double speed = sprinting ? 5.6 : 4.3;
            for (double release : new double[]{.1, .3, .55, .61, .8, .95, 1.1, 1.4}) {
                for (int fps : new int[]{30, 60, 144}) {
                    LocomotionController controller = new LocomotionController(library);
                    controller.sample(0, unknown(speed, 1, sprinting));
                    AnimationPose before = controller.sample(release, unknown(speed, 1, sprinting)).pose();
                    AnimationPose stopped = controller.sample(release, intent(0, 1, sprinting, false)).pose();
                    poseClose("release preserves the actual rendered pose", before, stopped);
                    AnimationPose afterTwoFrames = controller.sample(release + 2.0 / fps, intent(0, 1, sprinting, false)).pose();
                    check("release cannot hold a gait phase for two frames", maxRotationDelta(stopped, afterTwoFrames) > .1);
                }
            }
        }
    }

    private static void crouchInputReleaseHoldsItsAuthoredStance() {
        LocomotionController controller = new LocomotionController(library);
        LocomotionController.Motion crouch = new LocomotionController.Motion(1.3, 1, 0, false, true, true, 0,
                LocomotionController.MoveIntent.MOVING);
        LocomotionController.Motion released = new LocomotionController.Motion(1.3, 1, 0, false, true, true, 0,
                LocomotionController.MoveIntent.STOPPED);
        controller.sample(0, crouch);
        controller.sample(1, crouch);
        LocomotionController.Frame stop = controller.sample(1.1, released);
        check("crouch release selects stationary stance immediately", stop.clip().equals("sneak_enter"));
        close("crouch release holds the authored endpoint", 1, stop.phase());
        poseClose("crouch release blends into authored stance", library.sample("sneak_enter", .45),
                controller.sample(1.4, released).pose());
        check("fresh crouch input interrupts stationary hold", controller.sample(1.5, crouch).clip().equals("sneak_loop"));
    }

    private static void distancePhaseControlsOnlyMovingLoops() {
        LocomotionController controller = moving(4.3, 1, false);
        LocomotionController.Frame loop = controller.sample(1, unknown(4.3, 1, false), .25);
        close("world-distance phase selects exact moving-loop time", .25, loop.phase());
        poseClose("world-distance phase samples the authored pose", library.sample("walk_loop", .3), loop.pose());
        LocomotionController.Frame beforeWrap = controller.sample(1.01, unknown(4.3, 1, false), .99);
        close("distance phase can approach the end of a stride", .99, beforeWrap.phase());
        LocomotionController.Frame afterWrap = controller.sample(1.02, unknown(4.3, 1, false), .01);
        close("distance phase wraps to next stride", .01, afterWrap.phase());
        poseClose("wrapped distance phase samples the authored pose", library.sample("walk_loop", .012), afterWrap.pose());

        controller = new LocomotionController(library);
        controller.sample(0, unknown(4.3, 1, false), .8);
        LocomotionController.Frame start = controller.sample(.1, unknown(4.3, 1, false), .99);
        close("distance phase cannot speed up a start", .1 / .55, start.phase());
        controller.sample(.95, unknown(4.3, 1, false), .6);
        controller.sample(1, intent(0, 1, false, false), .8);
        LocomotionController.Frame stop = controller.sample(1.2, intent(0, 1, false, false), .99);
        close("distance phase cannot speed up a stop", .2 / .65, stop.phase());
        poseClose("stop retains real-time authored pose", library.sample("walk_stop", .2), stop.pose());

        controller = new LocomotionController(library);
        LocomotionController.Motion idle = unknown(0, 0, false);
        controller.sample(0, idle, .8);
        close("distance phase cannot move idle clock", .1 / 4, controller.sample(.1, idle, .8).phase());
        LocomotionController.Motion crouched = new LocomotionController.Motion(0, 0, 0, false, true, true, 0);
        controller.sample(.2, crouched, .8);
        close("distance phase cannot move held crouch", 1, controller.sample(1, crouched, .2).phase());
    }

    private static void poseClose(String message, AnimationPose expected, AnimationPose actual) {
        check(message, maxRotationDelta(expected, actual) < .0002);
        for (String bone : library.boneNames()) {
            AnimationPose.Vector a = expected.position(bone), b = actual.position(bone);
            check(message + " position " + bone, Math.abs(a.x() - b.x()) < .0002
                    && Math.abs(a.y() - b.y()) < .0002 && Math.abs(a.z() - b.z()) < .0002);
        }
    }

    private static void turningDuringRemoteInertiaCannotRestart() {
        LocomotionController controller = moving(4.3, 1, false);
        controller.sample(1, unknown(2.58, 1, false));
        check("second friction tick selects stop", controller.sample(1.05, unknown(1.548, 1, false)).clip().equals("walk_stop"));
        LocomotionController.Motion turningResidual = new LocomotionController.Motion(.9288, 0, 1, false, false, true, 90);
        check("turning in a decelerating stop cannot restart gait",
                controller.sample(1.1, turningResidual).clip().equals("walk_stop"));
        check("fresh remote acceleration interrupts the stop",
                controller.sample(1.15, new LocomotionController.Motion(2, 0, 1, false, false, true, 90))
                        .clip().equals("walk_strafe_left_loop"));
    }

    private static void steadyLowerSpeedsAndTurnsKeepWalking() {
        for (double[] speeds : new double[][]{{8.6, 4.3}, {4.3, 2.15}, {4.3, .1}, {.1, .1}}) {
            LocomotionController controller = moving(speeds[0], 1, false);
            for (int i = 0; i < 30; i++) {
                check("one speed reduction must keep walking: " + speeds[0] + " -> " + speeds[1],
                        controller.sample(1 + i / 60.0, unknown(speeds[1], 1, false)).clip().equals("walk_loop"));
            }
        }
        LocomotionController controller = moving(4.3, 1, false);
        controller.sample(1, unknown(3.9, 1, false));
        controller.sample(1.05, unknown(4.1, 1, false));
        check("small network speed jitter stays in gait", controller.sample(1.1, unknown(3.8, 1, false)).clip().equals("walk_loop"));
        controller.sample(1.15, new LocomotionController.Motion(2.2, 0, 1, false, false, true, 90));
        check("a direction change starts its gait rather than interpreting turn braking as stop",
                controller.sample(1.2, new LocomotionController.Motion(1.2, 0, 1, false, false, true, 90))
                        .clip().equals("walk_strafe_left_loop"));
    }

    private static void stableSlowedRemoteMovementRecovers() {
        for (int fps : new int[]{30, 60, 144}) {
            LocomotionController controller = moving(4.3, 1, false);
            controller.sample(1, unknown(2.3, 1, false));
            check("two slowing ticks provisionally select stop", controller.sample(1.05, unknown(1.25, 1, false))
                    .clip().equals("walk_stop"));
            double resumedAt = Double.NaN;
            for (int i = 1; i <= Math.ceil(.4 * fps); i++) {
                double elapsed = i / (double) fps;
                String clip = controller.sample(1.05 + elapsed, unknown(1.25, 1, false)).clip();
                if ((clip.equals("walk_start") || clip.equals("walk_loop")) && Double.isNaN(resumedAt)) resumedAt = elapsed;
            }
            check("stable lower moving velocity must clear inferred stop at " + fps + " fps; resumed=" + resumedAt,
                    Double.isFinite(resumedAt) && resumedAt >= .15 && resumedAt <= .22);
            check("stable lower moving velocity must reach its gait", controller.sample(2, unknown(1.25, 1, false))
                    .clip().equals("walk_loop"));
        }
    }

    private static void frictionStopStaysStoppedThroughIdle() {
        for (int fps : new int[]{30, 60, 144}) {
            LocomotionController controller = moving(4.3, 1, false);
            for (int i = 0; i <= 2 * fps; i++) {
                double elapsed = i / (double) fps;
                double speed = elapsed < 1 ? 4.3 * Math.pow(.6, 1 + (int) Math.floor(elapsed * 20 + 1e-7))
                        : i % 2 == 0 ? .02 : .04;
                LocomotionController.Frame frame = controller.sample(1 + elapsed, unknown(speed, 1, false));
                if (elapsed > .1) check("friction and stationary jitter cannot restart a gait at " + fps + " fps",
                        frame.clip().equals("walk_stop") || frame.clip().equals("idle"));
                if (elapsed > 1) check("completed friction stop remains idle", frame.clip().equals("idle"));
            }
        }
    }

    private static void zeroSpeedStopUsesRealTime() {
        for (int fps : new int[]{30, 60, 144}) {
            for (double residual : new double[]{0, .0001}) {
                LocomotionController controller = moving(4.3, 1, false);
                AnimationPose previous = controller.sample(1, intent(residual, 1, false, false)).pose();
                for (int i = 1; i <= .3 * fps; i++) {
                    double elapsed = i / (double) fps;
                    LocomotionController.Frame frame = controller.sample(1 + elapsed, intent(residual, 1, false, false));
                    check("stop at zero speed stays active", frame.active() && frame.clip().equals("walk_stop"));
                    close("zero speed cannot slow stop clock", elapsed / .65, frame.phase());
                    check("zero speed stop cannot freeze between frames", maxRotationDelta(previous, frame.pose()) > .01);
                    previous = frame.pose();
                }
            }
        }
    }

    private static void everyLoopKeepsItsPhaseAcrossFrameRates() {
        Object[][] gaits = {
                {"walk_loop", 4.3, 1., 0., false, false},
                {"walk_backward_loop", 4.3, -1., 0., false, false},
                {"walk_strafe_left_loop", 4.3, 0., 1., false, false},
                {"walk_strafe_right_loop", 4.3, 0., -1., false, false},
                {"walk_diag_fl_loop", 4.3, .707, .707, false, false},
                {"walk_diag_fr_loop", 4.3, .707, -.707, false, false},
                {"sprint_loop", 5.6, 1., 0., true, false},
                {"sneak_loop", 1.3, 1., 0., false, true},
                {"sneak_backward_loop", 1.3, -1., 0., false, true},
                {"sneak_strafe_left_loop", 1.3, 0., 1., false, true},
                {"sneak_strafe_right_loop", 1.3, 0., -1., false, true}};
        for (Object[] gait : gaits) {
            LocomotionController.Motion motion = new LocomotionController.Motion((double) gait[1], (double) gait[2],
                    (double) gait[3], (boolean) gait[4], (boolean) gait[5], true, 0);
            LocomotionController.Frame reference = null;
            for (int fps : new int[]{30, 60, 144}) {
                LocomotionController controller = new LocomotionController(library);
                LocomotionController.Frame frame = null;
                for (int i = 0; i <= fps * 2; i++) {
                    frame = controller.sample(i / (double) fps, motion);
                    check("normalized clip phase stays finite", Double.isFinite(frame.phase()) && frame.phase() >= 0 && frame.phase() <= 1);
                }
                check("seven-argument motion selects " + gait[0], frame.clip().equals(gait[0]));
                if (reference != null) {
                    close("gait phase depends on elapsed time", reference.phase(), frame.phase());
                    check("gait pose depends on elapsed time", maxRotationDelta(reference.pose(), frame.pose()) < .0002);
                }
                reference = frame;
            }
        }
        LocomotionController crouch = new LocomotionController(library);
        LocomotionController.Motion stationaryCrouch = new LocomotionController.Motion(0, 0, 0, false, true, true, 0);
        crouch.sample(0, stationaryCrouch);
        close("held crouch exposes endpoint phase", 1, crouch.sample(1, stationaryCrouch).phase());
    }

    private static void localReleaseIsImmediateAndLatched() {
        for (int fps : new int[]{30, 60, 144}) {
            for (String gait : new String[]{"walk", "walk_backward", "sprint"}) {
                boolean sprinting = gait.equals("sprint");
                double forward = gait.equals("walk_backward") ? -1 : 1;
                double speed = sprinting ? 5.6 : 4.3;
                LocomotionController controller = moving(speed, forward, sprinting);
                LocomotionController.Frame released = controller.sample(1, intent(speed, forward, sprinting, false));
                check(gait + " selects stop on the release frame at " + fps + " fps; actual=" + released.clip(),
                        released.clip().equals(gait + "_stop"));
                for (int i = 1; i <= .3 * fps; i++) {
                    double elapsed = i / (double) fps;
                    double residual = speed * Math.pow(.6, Math.floor(elapsed * 20));
                    LocomotionController.Frame stopping = controller.sample(1 + elapsed,
                            intent(residual, forward, sprinting, false));
                    check("release inertia cannot restart " + gait, stopping.clip().equals(gait + "_stop"));
                    close("stop clock advances in real time", elapsed / library.length(gait + "_stop"), stopping.phase());
                }
                LocomotionController.Frame resumed = controller.sample(1.31, intent(speed, forward, sprinting, true));
                check("moving input interrupts " + gait + " stop", resumed.clip().equals(gait + "_start"));
            }
        }
    }

    private static void remoteDecelerationStartsBeforeInertiaExpires() {
        for (int fps : new int[]{30, 60, 144}) {
            LocomotionController controller = moving(4.3, 1, false);
            LocomotionController.Frame frame = null;
            double firstStop = Double.NaN;
            for (int i = 0; i <= Math.ceil(.4 * fps); i++) {
                double elapsed = i / (double) fps;
                // Per-tick displacement observations repeat on render frames, just as in capture().
                double speed = 4.3 * Math.pow(.6, 1 + (int) Math.floor(elapsed * 20 + 1e-7));
                frame = controller.sample(1 + elapsed, unknown(speed, 1, false));
                if (frame.clip().equals("walk_stop") && Double.isNaN(firstStop)) firstStop = elapsed;
            }
            check("remote stop begins within 100 ms at " + fps + " fps; actual=" + firstStop,
                    Double.isFinite(firstStop) && firstStop < .1);
            check("inertia cannot restart walking after stop at " + fps + " fps", frame.clip().equals("walk_stop"));
        }
    }

    private static void suddenStopKeepsMovingDuringItsBlend() {
        for (int fps : new int[]{30, 60, 144}) {
            LocomotionController controller = moving(4.3, 1, false);
            AnimationPose stopped = controller.sample(1, unknown(0, 1, false)).pose();
            AnimationPose next = controller.sample(1 + 1.0 / fps, unknown(0, 1, false)).pose();
            check("stop body responds on the next frame at " + fps + " fps; degrees=" + maxRotationDelta(stopped, next),
                    maxRotationDelta(stopped, next) > .5);
        }
    }

    private static LocomotionController moving(double speed, double forward, boolean sprinting) {
        LocomotionController controller = new LocomotionController(library);
        controller.sample(0, unknown(speed, forward, sprinting));
        controller.sample(.95, unknown(speed, forward, sprinting));
        return controller;
    }

    private static LocomotionController.Motion unknown(double speed, double forward, boolean sprinting) {
        return new LocomotionController.Motion(speed, forward, 0, sprinting, false, true, 0);
    }

    private static LocomotionController.Motion intent(double speed, double forward, boolean sprinting, boolean moving) {
        return new LocomotionController.Motion(speed, forward, 0, sprinting, false, true, 0,
                moving ? LocomotionController.MoveIntent.MOVING : LocomotionController.MoveIntent.STOPPED);
    }

    private static void close(String message, double expected, double actual) {
        check(message + ": expected=" + expected + ", actual=" + actual, Math.abs(expected - actual) < 1e-6);
    }

    private static double maxRotationDelta(AnimationPose before, AnimationPose after) {
        double max = 0;
        for (String bone : library.boneNames()) {
            AnimationPose.Vector a = before.rotation(bone), b = after.rotation(bone);
            max = Math.max(max, Math.abs(a.x() - b.x()));
            max = Math.max(max, Math.abs(a.y() - b.y()));
            max = Math.max(max, Math.abs(a.z() - b.z()));
        }
        return max;
    }

    private static void check(String message, boolean value) {
        if (!value) throw new AssertionError(message);
    }
}
