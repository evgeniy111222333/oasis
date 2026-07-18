package ua.rp.chat;

/** Процедурное связывание траектории жёсткого молота с суставами персонажа. */
public final class HeavyHammerAnimation {
    public static final float DURATION_TICKS = 42.0f;
    public static final float IMPACT_TICK = 26.0f;
    public static final float EQUIP_DURATION_TICKS = 18.0f;
    public static final float UNEQUIP_DURATION_TICKS = 10.0f;
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
        return solve(HeavyHammerProceduralMotion.idle(ageTicks, locomotion),
                1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static Sample equip(float elapsedTicks, float ageTicks, float locomotion) {
        float progress = clamp(elapsedTicks / EQUIP_DURATION_TICKS, 0.0f, 1.0f);
        float poseWeight = smootherStep(clamp(progress / 0.82f, 0.0f, 1.0f));
        float offhandWeight = smootherStep(clamp((progress - 0.22f) / 0.50f, 0.0f, 1.0f));
        HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.equip(
                progress, ageTicks, locomotion);
        return solve(frame, poseWeight, offhandWeight, 1.0f, offhandWeight);
    }

    /** Полная RP-передача инструмента из подвеса в обе руки. */
    public static Sample draw(float position, float ageTicks, float locomotion) {
        float progress = clamp(position, 0.0f, 1.0f);
        float rightHandWeight = smootherStep(clamp((progress - 0.04f) / 0.24f, 0.0f, 1.0f));
        float offhandWeight = smootherStep(clamp((progress - 0.60f) / 0.22f, 0.0f, 1.0f));
        float gaitWeight = smootherStep(clamp((progress - 0.76f) / 0.24f, 0.0f, 1.0f));
        HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.draw(
                progress, ageTicks, locomotion);
        return solve(frame, rightHandWeight, offhandWeight, gaitWeight, offhandWeight);
    }

    public static Sample unequip(float elapsedTicks, float ageTicks, float locomotion) {
        float progress = clamp(elapsedTicks / UNEQUIP_DURATION_TICKS, 0.0f, 1.0f);
        float reverse = 1.0f - progress;
        float poseWeight = 1.0f - smootherStep(progress);
        float offhandWeight = 1.0f - smootherStep(clamp(progress / 0.42f, 0.0f, 1.0f));
        HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.equip(
                reverse, ageTicks, locomotion);
        return solve(frame, poseWeight, offhandWeight, 1.0f, offhandWeight);
    }

    public static Sample strike(float elapsedTicks) {
        float progress = clamp(elapsedTicks / DURATION_TICKS, 0.0f, 1.0f);
        HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.strike(progress);
        return solve(frame, 1.0f, 1.0f, 0.0f, supportGrip(frame.progress()));
    }

    public static Sample strike(float elapsedTicks, HeavyHammerProceduralMotion.Target target) {
        float progress = clamp(elapsedTicks / DURATION_TICKS, 0.0f, 1.0f);
        HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.strike(progress, target);
        return solve(frame, 1.0f, 1.0f, 0.0f, supportGrip(frame.progress()));
    }

    private static Sample solve(HeavyHammerProceduralMotion.Frame frame,
                                float poseWeight, float offhandWeight,
                                float gaitWeight, float wristSupport) {
        HeavyHammerGripSolver.Point requestedMain = point(frame.mainGrip());
        HeavyHammerGripSolver.Solution right = HeavyHammerGripSolver.solve(RIGHT_SHOULDER, requestedMain);
        HeavyHammerGripSolver.Point main = right.target();

        HeavyHammerProceduralMotion.Vec3 gripVector = frame.shaft()
                .scale(frame.gripDistance());
        HeavyHammerGripSolver.Point requestedOffhand = main.add(gripVector.x(), gripVector.y(), gripVector.z());
        HeavyHammerGripSolver.Solution left = HeavyHammerGripSolver.solve(LEFT_SHOULDER, requestedOffhand);

        HeavyHammerProceduralMotion.BodyPose body = frame.bodyPose();
        float rightWristTwist = lerp(-0.05f, 0.14f, wristSupport);
        float leftWristTwist = lerp(-0.10f, -0.64f, wristSupport);
        return new Sample(frame.progress(), body.torsoPitch(), body.torsoYaw(), body.torsoRoll(),
                body.headPitch(), body.headYaw(),
                body.rightLegPitch(), body.leftLegPitch(),
                body.rightLegRoll(), body.leftLegRoll(),
                body.rightKnee(), body.leftKnee(), body.stanceWidth(),
                right.upperX(), 0.0f, right.upperZ(), right.lowerX(), rightWristTwist,
                left.upperX(), 0.0f, left.upperZ(), left.lowerX(), leftWristTwist,
                gripVector.x(), gripVector.y(), gripVector.z(),
                right.clampDistance(), left.clampDistance(),
                frame.headAxis().x(), frame.headAxis().y(), frame.headAxis().z(),
                frame.shaft().x(), frame.shaft().y(), frame.shaft().z(),
                frame.depthAxis().x(), frame.depthAxis().y(), frame.depthAxis().z(),
                poseWeight, offhandWeight, gaitWeight);
    }

    private static float supportGrip(float progress) {
        float release = 1.0f - smootherStep(clamp(progress / 0.20f, 0.0f, 1.0f));
        float recover = smootherStep(clamp((progress - 0.82f) / 0.18f, 0.0f, 1.0f));
        return Math.max(release, recover);
    }

    private static float smootherStep(float value) {
        return value * value * value * (value * (value * 6.0f - 15.0f) + 10.0f);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
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
                         float rightX, float rightY, float rightZ, float rightLower, float rightWristTwist,
                         float leftX, float leftY, float leftZ, float leftLower, float leftWristTwist,
                         float gripX, float gripY, float gripZ,
                         float mainClampDistance, float gripClampDistance,
                         float headAxisX, float headAxisY, float headAxisZ,
                         float shaftX, float shaftY, float shaftZ,
                         float depthAxisX, float depthAxisY, float depthAxisZ,
                         float poseWeight, float offhandWeight, float gaitWeight) {
    }
}
