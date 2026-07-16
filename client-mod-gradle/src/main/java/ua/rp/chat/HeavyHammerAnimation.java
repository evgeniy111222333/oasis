package ua.rp.chat;

/** Процедурное связывание траектории жёсткого молота с суставами персонажа. */
public final class HeavyHammerAnimation {
    public static final float DURATION_TICKS = 34.0f;
    public static final float IMPACT_TICK = 21.0f;
    public static final HeavyHammerGripSolver.Point RIGHT_SHOULDER =
            new HeavyHammerGripSolver.Point(-5.0f, 2.0f, 0.0f);
    public static final HeavyHammerGripSolver.Point LEFT_SHOULDER =
            new HeavyHammerGripSolver.Point(5.0f, 2.0f, 0.0f);

    private HeavyHammerAnimation() {
    }

    public static Sample idle(float ageTicks) {
        return idle(ageTicks, 0.0f);
    }

    public static Sample idle(float ageTicks, float locomotion) {
        return solve(HeavyHammerProceduralMotion.idle(ageTicks, locomotion));
    }

    public static Sample strike(float elapsedTicks) {
        float progress = clamp(elapsedTicks / DURATION_TICKS, 0.0f, 1.0f);
        return solve(HeavyHammerProceduralMotion.strike(progress));
    }

    public static Sample strike(float elapsedTicks, HeavyHammerProceduralMotion.Target target) {
        float progress = clamp(elapsedTicks / DURATION_TICKS, 0.0f, 1.0f);
        return solve(HeavyHammerProceduralMotion.strike(progress, target));
    }

    private static Sample solve(HeavyHammerProceduralMotion.Frame frame) {
        HeavyHammerGripSolver.Point requestedMain = point(frame.mainGrip());
        HeavyHammerGripSolver.Solution right = HeavyHammerGripSolver.solve(RIGHT_SHOULDER, requestedMain);
        HeavyHammerGripSolver.Point main = right.target();

        HeavyHammerProceduralMotion.Vec3 gripVector = frame.shaft()
                .scale(frame.gripDistance());
        HeavyHammerGripSolver.Point requestedOffhand = main.add(gripVector.x(), gripVector.y(), gripVector.z());
        HeavyHammerGripSolver.Solution left = HeavyHammerGripSolver.solve(LEFT_SHOULDER, requestedOffhand);

        HeavyHammerProceduralMotion.BodyPose body = frame.bodyPose();
        return new Sample(frame.progress(), body.torsoPitch(), body.torsoYaw(), body.torsoRoll(),
                body.headPitch(), body.headYaw(),
                body.rightLegPitch(), body.leftLegPitch(),
                body.rightLegRoll(), body.leftLegRoll(),
                body.rightKnee(), body.leftKnee(), body.stanceWidth(),
                right.upperX(), 0.0f, right.upperZ(), right.lowerX(),
                left.upperX(), 0.0f, left.upperZ(), left.lowerX(),
                gripVector.x(), gripVector.y(), gripVector.z(),
                right.clampDistance(), left.clampDistance(),
                frame.headAxis().x(), frame.headAxis().y(), frame.headAxis().z(),
                frame.shaft().x(), frame.shaft().y(), frame.shaft().z(),
                frame.depthAxis().x(), frame.depthAxis().y(), frame.depthAxis().z());
    }

    private static HeavyHammerGripSolver.Point point(HeavyHammerProceduralMotion.Vec3 value) {
        return new HeavyHammerGripSolver.Point(value.x(), value.y(), value.z());
    }

    public static boolean impactReached(float previousTicks, float currentTicks) {
        return previousTicks < IMPACT_TICK && currentTicks >= IMPACT_TICK;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Sample(float progress, float bodyX, float bodyY, float bodyZ,
                         float headX, float headY,
                         float rightLegX, float leftLegX,
                         float rightLegZ, float leftLegZ,
                         float rightKnee, float leftKnee, float stanceWidth,
                         float rightX, float rightY, float rightZ, float rightLower,
                         float leftX, float leftY, float leftZ, float leftLower,
                         float gripX, float gripY, float gripZ,
                         float mainClampDistance, float gripClampDistance,
                         float headAxisX, float headAxisY, float headAxisZ,
                         float shaftX, float shaftY, float shaftZ,
                         float depthAxisX, float depthAxisY, float depthAxisZ) {
    }
}
