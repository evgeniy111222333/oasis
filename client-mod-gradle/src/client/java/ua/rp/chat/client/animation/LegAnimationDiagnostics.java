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
        boolean rightPantsKneePresent = hasChild(model.rightPants, "oasis_knee_cartilage");
        boolean leftPantsKneePresent = hasChild(model.leftPants, "oasis_knee_cartilage");
        float rightKnee = readChildDeg(model.rightLeg, "oasis_knee_cartilage");
        float leftKnee = readChildDeg(model.leftLeg, "oasis_knee_cartilage");
        float rightPantsKnee = readChildDeg(model.rightPants, "oasis_knee_cartilage");
        float leftPantsKnee = readChildDeg(model.leftPants, "oasis_knee_cartilage");
        float overlayDelta = Math.max(Math.abs(rightKnee - rightPantsKnee), Math.abs(leftKnee - leftPantsKnee));
        float overlaySyncScore = rightPantsKneePresent && leftPantsKneePresent ? clamp01(1.0f - overlayDelta / 3.0f) : 0.0f;
        float geometryScore = rightKneePresent && leftKneePresent && rightPantsKneePresent && leftPantsKneePresent ? 1.0f : 0.72f;
        float landingScore = runtime.landingCompression() <= 0.82f ? 1.0f : 0.85f;
        float visibilityScore = pose.totalIntensity() >= 0.22f ? 1.0f : clamp01(pose.totalIntensity() / 0.22f);
        float qualityScore = clamp01(rangeScore * 0.25f + jitterScore * 0.18f + geometryScore * 0.18f
                + landingScore * 0.14f + overlaySyncScore * 0.17f + visibilityScore * 0.08f);

        String warning = "ok";
        if (!rightKneePresent || !leftKneePresent) {
            warning = "knee_cartilage_missing";
        } else if (!rightPantsKneePresent || !leftPantsKneePresent) {
            warning = "pants_knee_cartilage_missing";
        } else if (overlayDelta > 0.75f) {
            warning = "pants_knee_desync";
        } else if (visibilityScore < 0.65f) {
            warning = "leg_animation_too_subtle";
        } else if (rangeScore < 0.70f) {
            warning = "leg_angle_range";
        } else if (jitterIndex > 12.0f) {
            warning = "leg_jitter";
        }

        lastSnapshot = new Snapshot(now, client.getUser().getName(), player.getUUID().toString(),
                runtime.movementMode(), runtime.horizontalSpeed(), runtime.speed01(), runtime.smoothedSpeed01(),
                runtime.idlePhase(), runtime.weightShiftPhase(), runtime.landingCompression(), runtime.stillTicks(), runtime.airborneTicks(),
                pose.totalIntensity(), rightX, model.rightLeg.yRot * RAD_TO_DEG, model.rightLeg.zRot * RAD_TO_DEG,
                leftX, model.leftLeg.yRot * RAD_TO_DEG, model.leftLeg.zRot * RAD_TO_DEG,
                pose.rightKneeXRot * RAD_TO_DEG, pose.leftKneeXRot * RAD_TO_DEG,
                rightKnee, leftKnee, rightPantsKnee, leftPantsKnee, rightKneePresent, leftKneePresent,
                rightPantsKneePresent, leftPantsKneePresent, overlayDelta, frameDelta, jitterIndex,
                rangeScore, jitterScore, geometryScore, landingScore, overlaySyncScore, visibilityScore, qualityScore, warning);

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

    private static boolean hasChild(ModelPart parent, String child) {
        try {
            parent.getChild(child);
            return true;
        } catch (RuntimeException ignored) {
            return false;
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
                           float idlePhase, float weightShiftPhase, float landingCompression, int stillTicks, int airborneTicks,
                           float poseIntensity, float rightLegXDeg, float rightLegYDeg, float rightLegZDeg,
                           float leftLegXDeg, float leftLegYDeg, float leftLegZDeg,
                           float rightKneeAppliedDeg, float leftKneeAppliedDeg,
                           float rightKneeVisualDeg, float leftKneeVisualDeg,
                           float rightPantsKneeVisualDeg, float leftPantsKneeVisualDeg,
                           boolean rightKneePresent, boolean leftKneePresent,
                           boolean rightPantsKneePresent, boolean leftPantsKneePresent,
                           float pantsOverlayDeltaDeg,
                           float maxFrameDeltaDeg, float jitterIndexDeg,
                           float rangeScore, float jitterScore, float geometryScore, float landingScore,
                           float overlaySyncScore, float visibilityScore,
                           float qualityScore, String warning) {
        static Snapshot empty() {
            return new Snapshot(0L, "", "", "idle", 0.0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, false, false, false, false, 0.0f, 0.0f, 0.0f,
                    1.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
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
                    + "  \"airborneTicks\": " + airborneTicks + ",\n"
                    + "  \"poseIntensity\": " + num(poseIntensity) + ",\n"
                    + "  \"rightLegDeg\": {\"x\": " + num(rightLegXDeg) + ", \"y\": " + num(rightLegYDeg) + ", \"z\": " + num(rightLegZDeg) + "},\n"
                    + "  \"leftLegDeg\": {\"x\": " + num(leftLegXDeg) + ", \"y\": " + num(leftLegYDeg) + ", \"z\": " + num(leftLegZDeg) + "},\n"
                    + "  \"kneeAppliedDeg\": {\"rightX\": " + num(rightKneeAppliedDeg) + ", \"leftX\": " + num(leftKneeAppliedDeg) + "},\n"
                    + "  \"kneeVisualDeg\": {\"rightX\": " + num(rightKneeVisualDeg) + ", \"leftX\": " + num(leftKneeVisualDeg) + "},\n"
                    + "  \"pantsKneeVisualDeg\": {\"rightX\": " + num(rightPantsKneeVisualDeg) + ", \"leftX\": " + num(leftPantsKneeVisualDeg) + "},\n"
                    + "  \"kneeCartilagePresent\": {\"right\": " + rightKneePresent + ", \"left\": " + leftKneePresent + "},\n"
                    + "  \"pantsKneeCartilagePresent\": {\"right\": " + rightPantsKneePresent + ", \"left\": " + leftPantsKneePresent + "},\n"
                    + "  \"pantsOverlayDeltaDeg\": " + num(pantsOverlayDeltaDeg) + ",\n"
                    + "  \"maxFrameDeltaDeg\": " + num(maxFrameDeltaDeg) + ",\n"
                    + "  \"jitterIndexDeg\": " + num(jitterIndexDeg) + ",\n"
                    + "  \"scores\": {\"range\": " + num(rangeScore) + ", \"jitter\": " + num(jitterScore)
                    + ", \"geometry\": " + num(geometryScore) + ", \"landing\": " + num(landingScore)
                    + ", \"overlaySync\": " + num(overlaySyncScore) + ", \"visibility\": " + num(visibilityScore)
                    + ", \"quality\": " + num(qualityScore) + "},\n"
                    + "  \"warning\": " + quote(warning) + "\n"
                    + "}\n";
        }
    }
}
