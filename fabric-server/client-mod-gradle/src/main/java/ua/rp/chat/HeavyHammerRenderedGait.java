package ua.rp.chat;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Проверяет фактическую иерархию таза, бедра, колена и стопы Minecraft. */
public final class HeavyHammerRenderedGait {
    private HeavyHammerRenderedGait() {
    }

    public static Result expected(HeavyHammerGait.Sample gait) {
        float stanceRoll = ArticulatedLimbLayout.stanceRoll(gait.stanceOffset());
        return evaluate(
                new LegPose(-ArticulatedLimbLayout.LEG_HIP_X, 12.0f, 0.0f,
                        gait.rightHipPitch(), 0.0f, gait.rightHipRoll() + stanceRoll,
                        gait.rightKnee(), 0.0f, 0.0f),
                new LegPose(ArticulatedLimbLayout.LEG_HIP_X, 12.0f, 0.0f,
                        gait.leftHipPitch(), 0.0f, gait.leftHipRoll() - stanceRoll,
                        gait.leftKnee(), 0.0f, 0.0f));
    }

    public static Result evaluate(LegPose right, LegPose left) {
        Vector3f rightFoot = foot(right);
        Vector3f leftFoot = foot(left);
        float separation = Math.abs(leftFoot.x - rightFoot.x);
        return new Result(rightFoot, leftFoot, separation,
                rootError(right, -ArticulatedLimbLayout.LEG_HIP_X),
                rootError(left, ArticulatedLimbLayout.LEG_HIP_X));
    }

    private static Vector3f foot(LegPose pose) {
        Quaternionf thigh = new Quaternionf().rotationZYX(
                pose.upperZ, pose.upperY, pose.upperX);
        Quaternionf shin = new Quaternionf().rotationZYX(
                pose.lowerZ, pose.lowerY, pose.lowerX);
        Vector3f upper = new Vector3f(0.0f, ArticulatedLimbLayout.LEG_KNEE_Y, 0.0f)
                .rotate(thigh);
        Vector3f lower = new Vector3f(0.0f,
                ArticulatedLimbLayout.LEG_FOOT_Y - ArticulatedLimbLayout.LEG_KNEE_Y, 0.0f)
                .rotate(new Quaternionf(thigh).mul(shin));
        return new Vector3f(pose.rootX + upper.x + lower.x,
                pose.rootY + upper.y + lower.y,
                pose.rootZ + upper.z + lower.z);
    }

    private static float rootError(LegPose pose, float expectedX) {
        float dx = pose.rootX - expectedX;
        float dy = pose.rootY - 12.0f;
        return (float) Math.sqrt(dx * dx + dy * dy + pose.rootZ * pose.rootZ);
    }

    public record LegPose(float rootX, float rootY, float rootZ,
                          float upperX, float upperY, float upperZ,
                          float lowerX, float lowerY, float lowerZ) {
    }

    public record Result(Vector3f rightFoot, Vector3f leftFoot,
                         float footCenterSeparation,
                         float rightHipRootError, float leftHipRootError) {
    }
}
