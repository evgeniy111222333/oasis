package ua.rp.chat;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Проверяет именно ту иерархию вращений, которую рендерит Minecraft:
 * корень плеча -> верхняя рука -> локоть -> предплечье -> центр ладони.
 */
public final class HeavyHammerRenderedRig {
    private HeavyHammerRenderedRig() {
    }

    public static Result expected(HeavyHammerAnimation.Sample sample) {
        return evaluate(sample,
                new ArmPose(HeavyHammerAnimation.RIGHT_SHOULDER.x(), HeavyHammerAnimation.RIGHT_SHOULDER.y(),
                        HeavyHammerAnimation.RIGHT_SHOULDER.z(), sample.rightX(), sample.rightY(), sample.rightZ(),
                        -sample.rightLower(),
                        ArticulatedLimbLayout.forearmYForTwoHandedGrip(sample.rightWristTwist()), 0.0f),
                new ArmPose(HeavyHammerAnimation.LEFT_SHOULDER.x(), HeavyHammerAnimation.LEFT_SHOULDER.y(),
                        HeavyHammerAnimation.LEFT_SHOULDER.z(), sample.leftX(), sample.leftY(), sample.leftZ(),
                        -sample.leftLower(),
                        ArticulatedLimbLayout.forearmYForTwoHandedGrip(sample.leftWristTwist()), 0.0f));
    }

    public static Result evaluate(HeavyHammerAnimation.Sample sample, ArmPose right, ArmPose left) {
        HeavyHammerGripSolver.Point renderedMain = hand(right);
        HeavyHammerGripSolver.Point renderedOffhand = hand(left);
        HeavyHammerGripSolver.Point targetMain = HeavyHammerGripSolver.hand(
                HeavyHammerAnimation.RIGHT_SHOULDER,
                sample.rightX(), sample.rightY(), sample.rightZ(), sample.rightLower());
        HeavyHammerGripSolver.Point targetOffhand = targetMain.add(
                sample.gripX(), sample.gripY(), sample.gripZ());
        return new Result(renderedMain, renderedOffhand, targetMain, targetOffhand,
                renderedMain.distanceTo(targetMain), renderedOffhand.distanceTo(targetOffhand),
                rootError(right, HeavyHammerAnimation.RIGHT_SHOULDER),
                rootError(left, HeavyHammerAnimation.LEFT_SHOULDER));
    }

    private static HeavyHammerGripSolver.Point hand(ArmPose pose) {
        Quaternionf upperRotation = new Quaternionf().rotationZYX(pose.upperZ, pose.upperY, pose.upperX);
        Quaternionf lowerRotation = new Quaternionf().rotationZYX(pose.lowerZ, pose.lowerY, pose.lowerX);
        Vector3f upper = new Vector3f(0.0f, HeavyHammerGripSolver.UPPER_LENGTH, 0.0f)
                .rotate(upperRotation);
        Vector3f lower = new Vector3f(0.0f, HeavyHammerGripSolver.LOWER_LENGTH, 0.0f)
                .rotate(new Quaternionf(upperRotation).mul(lowerRotation));
        return new HeavyHammerGripSolver.Point(
                pose.rootX + upper.x + lower.x,
                pose.rootY + upper.y + lower.y,
                pose.rootZ + upper.z + lower.z);
    }

    private static float rootError(ArmPose pose, HeavyHammerGripSolver.Point expected) {
        float dx = pose.rootX - expected.x();
        float dy = pose.rootY - expected.y();
        float dz = pose.rootZ - expected.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public record ArmPose(float rootX, float rootY, float rootZ,
                          float upperX, float upperY, float upperZ,
                          float lowerX, float lowerY, float lowerZ) {
    }

    public record Result(HeavyHammerGripSolver.Point renderedMain,
                         HeavyHammerGripSolver.Point renderedOffhand,
                         HeavyHammerGripSolver.Point targetMain,
                         HeavyHammerGripSolver.Point targetOffhand,
                         float mainGripError, float offhandGripError,
                         float rightShoulderRootError, float leftShoulderRootError) {
    }
}
