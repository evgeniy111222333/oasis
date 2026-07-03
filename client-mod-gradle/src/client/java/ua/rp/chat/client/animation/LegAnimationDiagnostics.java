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

public final class LegAnimationDiagnostics {
    private static final float RAD_TO_DEG = 57.29578f;

    private long lastWriteMs;
    private float lastRightLegXDeg;
    private float lastLeftLegXDeg;
    private float jitterIndex;
    private Snapshot lastSnapshot = Snapshot.empty();

    public void capture(Minecraft client, PlayerModel model, AvatarRenderState state, Player player,
                        LegRuntimeState runtime, LegPose pose, boolean rightKneePresent, boolean leftKneePresent) {
        if (client == null || model == null || state == null || player == null || runtime == null || pose == null) {
            return;
        }

        long now = System.currentTimeMillis();
        float rightX = model.rightLeg.xRot * RAD_TO_DEG;
        float leftX = model.leftLeg.xRot * RAD_TO_DEG;
        float frameDelta = Math.max(Math.abs(rightX - lastRightLegXDeg), Math.abs(leftX - lastLeftLegXDeg));
        lastRightLegXDeg = rightX;
        lastLeftLegXDeg = leftX;
        jitterIndex += (frameDelta - jitterIndex) * 0.16f;

        float rangeScore = Math.min(scoreAngle(rightX, -82.0f, 72.0f), scoreAngle(leftX, -82.0f, 72.0f));
        float jitterScore = clamp01(1.0f - jitterIndex / 20.0f);
        float geometryScore = rightKneePresent && leftKneePresent ? 1.0f : 0.82f;
        float landingScore = runtime.landingCompression() <= 0.82f ? 1.0f : 0.85f;
        float qualityScore = clamp01(rangeScore * 0.34f + jitterScore * 0.24f + geometryScore * 0.25f + landingScore * 0.17f);

        String warning = "ok";
        if (!rightKneePresent || !leftKneePresent) {
            warning = "knee_cartilage_missing";
        } else if (rangeScore < 0.70f) {
            warning = "leg_angle_range";
        } else if (jitterIndex > 12.0f) {
            warning = "leg_jitter";
        }

        float rightKnee = readChildDeg(model.rightLeg, "oasis_knee_cartilage");
        float leftKnee = readChildDeg(model.leftLeg, "oasis_knee_cartilage");
        lastSnapshot = new Snapshot(now, client.getUser().getName(), player.getUUID().toString(),
                runtime.movementMode(), runtime.horizontalSpeed(), runtime.speed01(), runtime.smoothedSpeed01(),
                runtime.idlePhase(), runtime.weightShiftPhase(), runtime.landingCompression(), runtime.stillTicks(),
                pose.totalIntensity(), rightX, model.rightLeg.yRot * RAD_TO_DEG, model.rightLeg.zRot * RAD_TO_DEG,
                leftX, model.leftLeg.yRot * RAD_TO_DEG, model.leftLeg.zRot * RAD_TO_DEG,
                pose.rightKneeXRot * RAD_TO_DEG, pose.leftKneeXRot * RAD_TO_DEG,
                rightKnee, leftKnee, rightKneePresent, leftKneePresent, frameDelta, jitterIndex,
                rangeScore, jitterScore, geometryScore, landingScore, qualityScore, warning);

        if (now - lastWriteMs >= 100L) {
            lastWriteMs = now;
            write(client, lastSnapshot);
        }
    }

    public String lastSnapshotJson() {
        return lastSnapshot.toJson();
    }

    private void write(Minecraft client, Snapshot snapshot) {
        try {
            Path debugDir = client.gameDirectory.toPath().resolve("oasis-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve("live-leg-animation.json"), snapshot.toJson(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static float readChildDeg(ModelPart parent, String child) {
        try {
            return parent.getChild(child).xRot * RAD_TO_DEG;
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    private static float scoreAngle(float deg, float min, float max) {
        if (deg >= min && deg <= max) {
            return 1.0f;
        }
        float distance = deg < min ? min - deg : deg - max;
        return clamp01(1.0f - distance / 32.0f);
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

    public record Snapshot(long timestampMs, String username, String uuid, String movementMode,
                           double horizontalSpeed, float speed01, float smoothedSpeed01,
                           float idlePhase, float weightShiftPhase, float landingCompression, int stillTicks,
                           float poseIntensity, float rightLegXDeg, float rightLegYDeg, float rightLegZDeg,
                           float leftLegXDeg, float leftLegYDeg, float leftLegZDeg,
                           float rightKneeAppliedDeg, float leftKneeAppliedDeg,
                           float rightKneeVisualDeg, float leftKneeVisualDeg,
                           boolean rightKneePresent, boolean leftKneePresent,
                           float maxFrameDeltaDeg, float jitterIndexDeg,
                           float rangeScore, float jitterScore, float geometryScore, float landingScore,
                           float qualityScore, String warning) {
        static Snapshot empty() {
            return new Snapshot(0L, "", "", "idle", 0.0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, false, false, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0.0f,
                    "not_captured");
        }

        public String toJson() {
            return "{\n"
                    + "  \"timestampMs\": " + timestampMs + ",\n"
                    + "  \"username\": " + quote(username) + ",\n"
                    + "  \"uuid\": " + quote(uuid) + ",\n"
                    + "  \"movementMode\": " + quote(movementMode) + ",\n"
                    + "  \"horizontalSpeed\": " + num(horizontalSpeed) + ",\n"
                    + "  \"speed01\": " + num(speed01) + ",\n"
                    + "  \"smoothedSpeed01\": " + num(smoothedSpeed01) + ",\n"
                    + "  \"idlePhase\": " + num(idlePhase) + ",\n"
                    + "  \"weightShiftPhase\": " + num(weightShiftPhase) + ",\n"
                    + "  \"landingCompression\": " + num(landingCompression) + ",\n"
                    + "  \"stillTicks\": " + stillTicks + ",\n"
                    + "  \"poseIntensity\": " + num(poseIntensity) + ",\n"
                    + "  \"rightLegDeg\": {\"x\": " + num(rightLegXDeg) + ", \"y\": " + num(rightLegYDeg) + ", \"z\": " + num(rightLegZDeg) + "},\n"
                    + "  \"leftLegDeg\": {\"x\": " + num(leftLegXDeg) + ", \"y\": " + num(leftLegYDeg) + ", \"z\": " + num(leftLegZDeg) + "},\n"
                    + "  \"kneeAppliedDeg\": {\"rightX\": " + num(rightKneeAppliedDeg) + ", \"leftX\": " + num(leftKneeAppliedDeg) + "},\n"
                    + "  \"kneeVisualDeg\": {\"rightX\": " + num(rightKneeVisualDeg) + ", \"leftX\": " + num(leftKneeVisualDeg) + "},\n"
                    + "  \"kneeCartilagePresent\": {\"right\": " + rightKneePresent + ", \"left\": " + leftKneePresent + "},\n"
                    + "  \"maxFrameDeltaDeg\": " + num(maxFrameDeltaDeg) + ",\n"
                    + "  \"jitterIndexDeg\": " + num(jitterIndexDeg) + ",\n"
                    + "  \"scores\": {\"range\": " + num(rangeScore) + ", \"jitter\": " + num(jitterScore)
                    + ", \"geometry\": " + num(geometryScore) + ", \"landing\": " + num(landingScore)
                    + ", \"quality\": " + num(qualityScore) + "},\n"
                    + "  \"warning\": " + quote(warning) + "\n"
                    + "}\n";
        }
    }
}
