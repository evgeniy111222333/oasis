package ua.rp.chat.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ArmAnimationDiagnostics {
    private static final float RAD_TO_DEG = 57.29578f;

    private long lastWriteMs;
    private long lastMetricsMs;
    private float lastRightXDeg;
    private float lastLeftXDeg;
    private float smoothedJitter;
    private Snapshot lastSnapshot = Snapshot.empty();

    public void capture(Minecraft client, PlayerModel model, AvatarRenderState state, Player player,
                        ArmRuntimeState runtime, ArmPose pose, boolean firstPersonBody,
                        boolean rightForearmPresent, boolean leftForearmPresent) {
        if (client == null || model == null || state == null || player == null || runtime == null || pose == null) {
            return;
        }

        long now = System.currentTimeMillis();
        float rightXDeg = model.rightArm.xRot * RAD_TO_DEG;
        float leftXDeg = model.leftArm.xRot * RAD_TO_DEG;
        float frameDelta = Math.max(Math.abs(rightXDeg - lastRightXDeg), Math.abs(leftXDeg - lastLeftXDeg));
        if (lastMetricsMs == 0L) {
            frameDelta = 0.0f;
        }
        lastRightXDeg = rightXDeg;
        lastLeftXDeg = leftXDeg;
        lastMetricsMs = now;
        smoothedJitter += (frameDelta - smoothedJitter) * 0.18f;

        float rangeScore = rangeScore(model);
        float jitterScore = clamp01(1.0f - smoothedJitter / 22.0f);
        float continuityScore = clamp01(1.0f - frameDelta / 32.0f);
        float geometryScore = !firstPersonBody || (rightForearmPresent && leftForearmPresent) ? 1.0f : 0.72f;
        float qualityScore = clamp01(rangeScore * 0.38f + jitterScore * 0.27f + continuityScore * 0.20f + geometryScore * 0.15f);

        String warning = "ok";
        if (!firstPersonBody) {
            warning = "not_first_person_body";
        } else if (rangeScore < 0.68f) {
            warning = "angle_range";
        } else if (smoothedJitter > 14.0f) {
            warning = "jitter";
        } else if (!rightForearmPresent || !leftForearmPresent) {
            warning = "vanilla_arm_model";
        }

        lastSnapshot = new Snapshot(now, client.getUser().getName(), player.getUUID().toString(), firstPersonBody,
                runtime.movementMode(), runtime.itemProfile().group(), runtime.itemProfile().id(),
                runtime.horizontalSpeed(), runtime.speed01(), runtime.smoothedSpeed01(), runtime.stridePhase(),
                runtime.breathPhase(), runtime.yawDelta(), runtime.pitchDelta(), runtime.yawInertia(),
                runtime.pitchInertia(), runtime.landingImpulse(), runtime.actionImpulse(),
                pose.totalIntensity(), rightXDeg, model.rightArm.yRot * RAD_TO_DEG, model.rightArm.zRot * RAD_TO_DEG,
                leftXDeg, model.leftArm.yRot * RAD_TO_DEG, model.leftArm.zRot * RAD_TO_DEG,
                readForearmDeg(model.rightArm, "oasis_forearm"), readForearmDeg(model.leftArm, "oasis_forearm"),
                rightForearmPresent, leftForearmPresent, frameDelta, smoothedJitter, rangeScore,
                jitterScore, continuityScore, geometryScore, qualityScore, warning);

        if (now - lastWriteMs >= 100L) {
            lastWriteMs = now;
            write(client, lastSnapshot);
        }
    }

    public Snapshot lastSnapshot() {
        return lastSnapshot;
    }

    public String lastSnapshotJson() {
        return lastSnapshot.toJson();
    }

    private void write(Minecraft client, Snapshot snapshot) {
        try {
            Path debugDir = client.gameDirectory.toPath().resolve("oasis-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve("live-arm-animation.json"), snapshot.toJson(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static float rangeScore(PlayerModel model) {
        float worst = 1.0f;
        worst = Math.min(worst, scoreAngle(model.rightArm.xRot * RAD_TO_DEG, -145.0f, 95.0f));
        worst = Math.min(worst, scoreAngle(model.leftArm.xRot * RAD_TO_DEG, -145.0f, 95.0f));
        worst = Math.min(worst, scoreAngle(model.rightArm.yRot * RAD_TO_DEG, -72.0f, 72.0f));
        worst = Math.min(worst, scoreAngle(model.leftArm.yRot * RAD_TO_DEG, -72.0f, 72.0f));
        worst = Math.min(worst, scoreAngle(model.rightArm.zRot * RAD_TO_DEG, -88.0f, 88.0f));
        worst = Math.min(worst, scoreAngle(model.leftArm.zRot * RAD_TO_DEG, -88.0f, 88.0f));
        return worst;
    }

    private static float scoreAngle(float deg, float min, float max) {
        if (deg >= min && deg <= max) {
            return 1.0f;
        }
        float distance = deg < min ? min - deg : deg - max;
        return clamp01(1.0f - distance / 36.0f);
    }

    private static float readForearmDeg(ModelPart arm, String child) {
        try {
            return arm.getChild(child).xRot * RAD_TO_DEG;
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String num(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record Snapshot(long timestampMs, String username, String uuid, boolean firstPersonBody,
                           String movementMode, String itemGroup, String itemId,
                           double horizontalSpeed, float speed01, float smoothedSpeed01,
                           float stridePhase, float breathPhase, float yawDeltaDeg, float pitchDeltaDeg,
                           float yawInertia, float pitchInertia, float landingImpulse, float actionImpulse,
                           float poseIntensity, float rightArmXDeg, float rightArmYDeg, float rightArmZDeg,
                           float leftArmXDeg, float leftArmYDeg, float leftArmZDeg,
                           float rightForearmXDeg, float leftForearmXDeg,
                           boolean rightForearmPresent, boolean leftForearmPresent,
                           float maxFrameDeltaDeg, float jitterIndexDeg, float rangeScore,
                           float jitterScore, float continuityScore, float geometryScore,
                           float qualityScore, String warning) {
        static Snapshot empty() {
            return new Snapshot(0L, "", "", false, "idle", "empty", "empty", 0.0, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, false, false,
                    0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, "not_captured");
        }

        public String toJson() {
            return "{\n"
                    + "  \"timestampMs\": " + timestampMs + ",\n"
                    + "  \"username\": " + quote(username) + ",\n"
                    + "  \"uuid\": " + quote(uuid) + ",\n"
                    + "  \"firstPersonBody\": " + firstPersonBody + ",\n"
                    + "  \"movementMode\": " + quote(movementMode) + ",\n"
                    + "  \"itemGroup\": " + quote(itemGroup) + ",\n"
                    + "  \"itemId\": " + quote(itemId) + ",\n"
                    + "  \"horizontalSpeed\": " + num(horizontalSpeed) + ",\n"
                    + "  \"speed01\": " + num(speed01) + ",\n"
                    + "  \"smoothedSpeed01\": " + num(smoothedSpeed01) + ",\n"
                    + "  \"stridePhase\": " + num(stridePhase) + ",\n"
                    + "  \"breathPhase\": " + num(breathPhase) + ",\n"
                    + "  \"yawDeltaDeg\": " + num(yawDeltaDeg) + ",\n"
                    + "  \"pitchDeltaDeg\": " + num(pitchDeltaDeg) + ",\n"
                    + "  \"yawInertia\": " + num(yawInertia) + ",\n"
                    + "  \"pitchInertia\": " + num(pitchInertia) + ",\n"
                    + "  \"landingImpulse\": " + num(landingImpulse) + ",\n"
                    + "  \"actionImpulse\": " + num(actionImpulse) + ",\n"
                    + "  \"poseIntensity\": " + num(poseIntensity) + ",\n"
                    + "  \"rightArmDeg\": {\"x\": " + num(rightArmXDeg) + ", \"y\": " + num(rightArmYDeg) + ", \"z\": " + num(rightArmZDeg) + "},\n"
                    + "  \"leftArmDeg\": {\"x\": " + num(leftArmXDeg) + ", \"y\": " + num(leftArmYDeg) + ", \"z\": " + num(leftArmZDeg) + "},\n"
                    + "  \"forearmDeg\": {\"rightX\": " + num(rightForearmXDeg) + ", \"leftX\": " + num(leftForearmXDeg) + "},\n"
                    + "  \"forearmPresent\": {\"right\": " + rightForearmPresent + ", \"left\": " + leftForearmPresent + "},\n"
                    + "  \"maxFrameDeltaDeg\": " + num(maxFrameDeltaDeg) + ",\n"
                    + "  \"jitterIndexDeg\": " + num(jitterIndexDeg) + ",\n"
                    + "  \"scores\": {\"range\": " + num(rangeScore) + ", \"jitter\": " + num(jitterScore)
                    + ", \"continuity\": " + num(continuityScore) + ", \"geometry\": " + num(geometryScore)
                    + ", \"quality\": " + num(qualityScore) + "},\n"
                    + "  \"warning\": " + quote(warning) + "\n"
                    + "}\n";
        }
    }
}
