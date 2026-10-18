package ua.rp.chat.animation;

import java.util.Objects;

/** One controller per entity. All clocks use entity age in seconds, never render frame count. */
public final class LocomotionController {
    /** Local input is authoritative; replicated movement has no input information. */
    public enum MoveIntent { UNKNOWN, MOVING, STOPPED }

    public record Motion(double speed, double forward, double left, boolean sprinting,
                         boolean crouching, boolean grounded, float bodyYaw, MoveIntent moveIntent) {
        public Motion(double speed, double forward, double left, boolean sprinting,
                      boolean crouching, boolean grounded, float bodyYaw) {
            this(speed, forward, left, sprinting, crouching, grounded, bodyYaw, MoveIntent.UNKNOWN);
        }

        public Motion {
            Objects.requireNonNull(moveIntent, "moveIntent");
            if (!Double.isFinite(speed) || speed < 0 || !Double.isFinite(forward)
                    || !Double.isFinite(left) || !Float.isFinite(bodyYaw)) {
                throw new IllegalArgumentException("Motion must contain finite directions, yaw and nonnegative speed");
            }
        }
    }

    public record Frame(AnimationPose pose, String clip, boolean active, double phase) {
        public Frame(AnimationPose pose, String clip, boolean active) { this(pose, clip, active, 0); }
    }

    private static final Frame INACTIVE = new Frame(AnimationPose.IDENTITY, "", false);
    private static final double MOVING_SPEED = .05;
    private static final double FADE_SECONDS = .14;
    private static final double STOP_FADE_SECONDS = .09;
    private static final double DECELERATION_WINDOW = .12;
    private static final double STEADY_MOVEMENT_SECONDS = .18;
    private static final double TURN_THRESHOLD_DEGREES = 45;
    private final AnimationLibrary library;
    private boolean initialized;
    private double previousSeconds;
    private Motion previousMotion;
    private double stationaryYaw;
    private Mode mode = Mode.IDLE;
    private Movement movement = Movement.IDLE;
    private Movement desired = Movement.IDLE;
    private String clip = "idle";
    private double clipTime;
    private AnimationPose blendSource;
    private double blendTime;
    private double blendDuration = FADE_SECONDS;
    private String continuingClip;
    private double continuingTime;
    private double continuingRate;
    private boolean continuingLoop;
    private double observedSpeed;
    private double observedSeconds;
    private double decelerationSpeed;
    private int decelerationSamples;
    private boolean inferredStop;

    public LocomotionController(AnimationLibrary library) {
        this.library = Objects.requireNonNull(library, "library");
        for (Movement value : Movement.values()) {
            library.length(value.loop);
            if (value.start != null) library.length(value.start);
            if (value.stop != null) library.length(value.stop);
        }
        library.length("sneak_exit");
        library.length("turn_left_90");
        library.length("turn_right_90");
    }

    /** Unsupported states reset playback; the renderer can use its native pose while inactive. */
    public Frame sample(double seconds, Motion motion) {
        return sample(seconds, motion, Double.NaN);
    }

    /** A finite world-distance phase replaces only moving loops; one-shots retain their real clocks. */
    public Frame sample(double seconds, Motion motion, double normalizedLoopPhase) {
        Objects.requireNonNull(motion, "motion");
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Entity age must be finite");
        if (!motion.grounded) {
            initialized = false;
            previousMotion = null;
            blendSource = null;
            continuingClip = null;
            return INACTIVE;
        }
        if (!initialized || seconds < previousSeconds - .000001) {
            initialized = true;
            previousSeconds = seconds;
            previousMotion = motion;
            stationaryYaw = 0;
            mode = Mode.IDLE;
            movement = desired = Movement.IDLE;
            clip = "idle";
            clipTime = 0;
            blendSource = null;
            blendTime = 0;
            continuingClip = null;
            resetObservations(seconds, motion.speed);
            inferredStop = false;
        } else {
            advance(Math.max(0, seconds - previousSeconds), previousMotion);
            if (stationary(previousMotion) && stationary(motion)) {
                stationaryYaw += wrappedYaw(motion.bodyYaw - previousMotion.bodyYaw);
            } else stationaryYaw = 0;
            previousSeconds = seconds;
        }
        desired = select(motion);
        applyMoveIntent(seconds, motion);
        applyInput(motion);
        if (mode == Mode.LOOP && Double.isFinite(normalizedLoopPhase)) {
            clipTime = (normalizedLoopPhase - Math.floor(normalizedLoopPhase)) * library.length(clip);
        }
        previousMotion = motion;
        return new Frame(pose(), clip, true, Math.max(0, Math.min(1, clipTime / library.length(clip))));
    }

    /** Render frames repeat tick displacement; count distinct velocity drops, never render derivatives. */
    private void applyMoveIntent(double seconds, Motion motion) {
        if (motion.moveIntent != MoveIntent.UNKNOWN) {
            inferredStop = false;
            resetObservations(seconds, motion.speed);
            if (motion.moveIntent == MoveIntent.STOPPED) {
                desired = motion.crouching ? Movement.CROUCH_IDLE : Movement.IDLE;
            }
            return;
        }
        if (previousMotion.crouching != motion.crouching
                || !inferredStop && !sameDirection(previousMotion, motion)) {
            inferredStop = false;
            resetObservations(seconds, motion.speed);
        } else if (Math.abs(motion.speed - observedSpeed) > .001) {
            double previousSpeed = observedSpeed;
            boolean slowing = motion.speed < previousSpeed * .9 && previousSpeed - motion.speed > .015;
            if (slowing) {
                if (decelerationSamples == 0 || seconds - observedSeconds > DECELERATION_WINDOW) {
                    decelerationSpeed = previousSpeed;
                    decelerationSamples = 0;
                }
                decelerationSamples++;
                if (decelerationSamples >= 2 && motion.speed < decelerationSpeed * .55) inferredStop = true;
            } else if (motion.speed > previousSpeed * 1.1 && motion.speed - previousSpeed > .025) {
                inferredStop = false;
                decelerationSamples = 0;
            }
            observedSpeed = motion.speed;
            observedSeconds = seconds;
        } else if (seconds - observedSeconds > DECELERATION_WINDOW) {
            // A lower but steady walking speed is a gait change, not a key release.
            decelerationSamples = 0;
            if (motion.speed > MOVING_SPEED && seconds - observedSeconds >= STEADY_MOVEMENT_SECONDS) {
                inferredStop = false;
            }
        }
        if (inferredStop) desired = motion.crouching ? Movement.CROUCH_IDLE : Movement.IDLE;
        else if (decelerationSamples > 0 && (mode == Mode.START || mode == Mode.LOOP)
                && !movement.crouched && !motion.crouching && desired != Movement.IDLE) {
            // Preserve the outgoing gait while a second tick distinguishes release from slowing down.
            desired = movement;
        }
    }

    private void resetObservations(double seconds, double speed) {
        observedSpeed = decelerationSpeed = speed;
        observedSeconds = seconds;
        decelerationSamples = 0;
    }

    private static boolean sameDirection(Motion before, Motion after) {
        double a = Math.hypot(before.forward, before.left), b = Math.hypot(after.forward, after.left);
        return a < .2 || b < .2 || (before.forward * after.forward + before.left * after.left) / (a * b) > .7;
    }

    private void applyInput(Motion motion) {
        if (motion.crouching) {
            if (!crouchState()) {
                change(Mode.ENTER, "sneak_enter", 0, desired);
            } else if (mode != Mode.ENTER && movement != desired) {
                if (desired == Movement.CROUCH_IDLE) {
                    change(Mode.HOLD, "sneak_enter", library.length("sneak_enter"), desired);
                } else changeLoop(desired, true);
            }
            stationaryYaw = 0;
            return;
        }
        if (crouchState()) {
            change(Mode.EXIT, "sneak_exit", 0, Movement.IDLE);
            stationaryYaw = 0;
            return;
        }
        if (mode == Mode.EXIT) return;
        if (desired != Movement.IDLE) {
            if ((mode != Mode.START && mode != Mode.LOOP) || movement != desired) startMovement(desired);
            stationaryYaw = 0;
        } else if (mode == Mode.START || mode == Mode.LOOP) {
            change(Mode.STOP, movement.stop, 0, movement);
        } else if (mode == Mode.IDLE && Math.abs(stationaryYaw) >= TURN_THRESHOLD_DEGREES) {
            change(Mode.TURN, stationaryYaw < 0 ? "turn_left_90" : "turn_right_90", 0, Movement.IDLE);
            stationaryYaw = 0;
        }
    }

    private boolean crouchState() {
        return mode == Mode.ENTER || mode == Mode.HOLD || mode == Mode.LOOP && movement.crouched;
    }

    private void startMovement(Movement goal) {
        if (goal.start != null) change(Mode.START, goal.start, 0, goal);
        else changeLoop(goal, true);
    }

    private void changeLoop(Movement goal, boolean preservePhase) {
        double phase = preservePhase && mode == Mode.LOOP ? clipTime / library.length(clip) : 0;
        change(Mode.LOOP, goal.loop, phase * library.length(goal.loop), goal);
    }

    /** Consume transition boundaries exactly, carrying any remaining real time into the next clip. */
    private void advance(double delta, Motion motion) {
        while (delta > 0) {
            if (mode == Mode.LOOP || mode == Mode.IDLE || mode == Mode.HOLD) {
                advanceBlend(delta);
                if (mode != Mode.HOLD) {
                    double rate = mode == Mode.IDLE ? 1 : motion.speed / movement.referenceSpeed;
                    double length = library.length(clip);
                    // Reduce wall time before multiplication to keep long gaps finite.
                    if (rate > 0) clipTime = (clipTime + (delta % (length / rate)) * rate) % length;
                }
                return;
            }
            double toEnd = Math.max(0, library.length(clip) - clipTime);
            double step = Math.min(delta, toEnd);
            clipTime += step;
            advanceBlend(step);
            delta -= step;
            if (step + .000000001 < toEnd) return;
            finishOneShot();
        }
    }

    private void finishOneShot() {
        switch (mode) {
            case START -> changeLoop(movement, false);
            case STOP, TURN -> change(Mode.IDLE, "idle", 0, Movement.IDLE);
            case ENTER -> {
                if (desired == Movement.CROUCH_IDLE) {
                    change(Mode.HOLD, "sneak_enter", library.length("sneak_enter"), desired);
                } else changeLoop(desired, false);
            }
            case EXIT -> {
                if (desired == Movement.IDLE) change(Mode.IDLE, "idle", 0, Movement.IDLE);
                else startMovement(desired);
            }
            default -> throw new IllegalStateException("Unexpected one-shot state: " + mode);
        }
    }

    private void advanceBlend(double delta) {
        if (blendSource == null) return;
        blendTime += delta;
        if (continuingClip != null) {
            continuingTime += delta * continuingRate;
            if (continuingLoop) continuingTime %= library.length(continuingClip);
        }
        if (blendTime >= blendDuration) {
            blendSource = null;
            continuingClip = null;
        }
    }

    private void change(Mode nextMode, String nextClip, double nextTime, Movement nextMovement) {
        AnimationPose actual = pose();
        boolean continueGait = nextMode == Mode.STOP && (mode == Mode.START || mode == Mode.LOOP);
        continuingClip = continueGait ? clip : null;
        continuingTime = clipTime;
        continuingLoop = mode == Mode.LOOP;
        // The outgoing leg must keep moving while the stop takes over, even after speed reaches zero.
        continuingRate = mode == Mode.LOOP
                ? Math.max(.5, previousMotion.speed / movement.referenceSpeed) : 1;
        mode = nextMode;
        clip = nextClip;
        clipTime = nextTime;
        movement = nextMovement;
        blendSource = actual;
        blendTime = 0;
        blendDuration = nextMode == Mode.STOP ? STOP_FADE_SECONDS : FADE_SECONDS;
    }

    private AnimationPose pose() {
        if (blendSource != null && blendTime <= 0) return blendSource;
        AnimationPose target = library.sample(clip, clipTime);
        if (blendSource == null) return target;
        AnimationPose source = blendSource;
        if (continuingClip != null) {
            source = AnimationPose.blend(blendSource, library.sample(continuingClip, continuingTime),
                    Math.min(1, blendTime / .025));
        }
        double amount = Math.min(1, blendTime / blendDuration);
        amount = amount * amount * (3 - 2 * amount);
        return AnimationPose.blend(source, target, amount);
    }

    private static boolean stationary(Motion motion) {
        return motion.speed <= MOVING_SPEED && !motion.crouching && motion.grounded;
    }

    private static double wrappedYaw(double angle) {
        angle %= 360;
        if (angle > 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static Movement select(Motion motion) {
        if (motion.speed <= MOVING_SPEED) return motion.crouching ? Movement.CROUCH_IDLE : Movement.IDLE;
        double forward = motion.forward, left = motion.left;
        if (motion.crouching) {
            if (forward < -.35 && Math.abs(forward) >= Math.abs(left)) return Movement.CROUCH_BACKWARD;
            if (Math.abs(left) > .35 && Math.abs(left) > Math.abs(forward)) {
                return left > 0 ? Movement.CROUCH_LEFT : Movement.CROUCH_RIGHT;
            }
            return Movement.CROUCH_FORWARD;
        }
        if (forward < -.35 && Math.abs(forward) >= Math.abs(left)) return Movement.BACKWARD;
        if (motion.sprinting && forward > .35) return Movement.SPRINT;
        if (forward > .35 && Math.abs(left) > .35) return left > 0 ? Movement.DIAG_LEFT : Movement.DIAG_RIGHT;
        if (Math.abs(left) > .35) return left > 0 ? Movement.LEFT : Movement.RIGHT;
        return Movement.WALK;
    }

    private enum Mode { IDLE, START, LOOP, STOP, ENTER, HOLD, EXIT, TURN }

    private enum Movement {
        IDLE("idle", null, null, false, 4.3),
        WALK("walk_loop", "walk_start", "walk_stop", false, 4.3),
        BACKWARD("walk_backward_loop", "walk_backward_start", "walk_backward_stop", false, 4.3),
        LEFT("walk_strafe_left_loop", null, "walk_stop", false, 4.3),
        RIGHT("walk_strafe_right_loop", null, "walk_stop", false, 4.3),
        DIAG_LEFT("walk_diag_fl_loop", null, "walk_stop", false, 4.3),
        DIAG_RIGHT("walk_diag_fr_loop", null, "walk_stop", false, 4.3),
        SPRINT("sprint_loop", "sprint_start", "sprint_stop", false, 5.6),
        CROUCH_IDLE("sneak_enter", null, null, true, 1.3),
        CROUCH_FORWARD("sneak_loop", null, null, true, 1.3),
        CROUCH_BACKWARD("sneak_backward_loop", null, null, true, 1.3),
        CROUCH_LEFT("sneak_strafe_left_loop", null, null, true, 1.3),
        CROUCH_RIGHT("sneak_strafe_right_loop", null, null, true, 1.3);

        final String loop, start, stop;
        final boolean crouched;
        final double referenceSpeed;

        Movement(String loop, String start, String stop, boolean crouched, double referenceSpeed) {
            this.loop = loop;
            this.start = start;
            this.stop = stop;
            this.crouched = crouched;
            this.referenceSpeed = referenceSpeed;
        }
    }
}
