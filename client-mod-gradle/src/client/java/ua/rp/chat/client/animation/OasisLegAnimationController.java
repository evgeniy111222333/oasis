package ua.rp.chat.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;
import ua.rp.chat.client.vitals.VitalsClientState;

public final class OasisLegAnimationController {
    private static final OasisLegAnimationController INSTANCE = new OasisLegAnimationController();
    private static final float PI2 = (float) (Math.PI * 2.0);

    private final LegRuntimeState runtime = new LegRuntimeState();
    private final LegAnimationDiagnostics diagnostics = new LegAnimationDiagnostics();

    private OasisLegAnimationController() {
    }

    public static OasisLegAnimationController getInstance() {
        return INSTANCE;
    }

    public void clientTick(Minecraft client) {
        runtime.update(client);
    }

    public String lastDiagnosticsJson() {
        return diagnostics.lastSnapshotJson();
    }

    public void apply(PlayerModel model, AvatarRenderState state, Player player, boolean localPlayer) {
        Minecraft client = Minecraft.getInstance();
        if (model == null || state == null || player == null || client == null || client.player != player
                || !localPlayer || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        LegPose pose = buildPose(state);
        applyPose(model, pose);

        boolean rightKneePresent = hasChild(model.rightLeg, "oasis_knee_cartilage");
        boolean leftKneePresent = hasChild(model.leftLeg, "oasis_knee_cartilage");
        diagnostics.capture(client, model, state, player, runtime, pose, rightKneePresent, leftKneePresent);
    }

    private LegPose buildPose(AvatarRenderState state) {
        LegPose pose = new LegPose();
        float speed = clamp01(Math.max(runtime.smoothedSpeed01(), state.walkAnimationSpeed * 2.4f));
        float idle = clamp01(1.0f - speed * 1.35f);
        float crouch = state.isCrouching ? 1.0f : 0.0f;
        float landing = runtime.landingCompression();
        float fatigue = clamp01(VitalsClientState.getFatigue() / 100.0f);
        float bloodLoss = 1.0f - VitalsClientState.getBlood01();
        float pain = clamp01(VitalsClientState.getPain() / 100.0f);
        float weakness = clamp01(Math.max(fatigue * 0.55f, Math.max(bloodLoss * 0.70f, pain * 0.45f)));

        applyIdleLife(pose, idle, weakness);
        applyMovementStance(pose, speed, crouch);
        applyLandingCompression(pose, landing, weakness);

        return pose;
    }

    private void applyIdleLife(LegPose pose, float idle, float weakness) {
        if (idle <= 0.01f) {
            return;
        }

        float shift = (float) Math.sin(runtime.weightShiftPhase() * PI2);
        float slow = (float) Math.sin(runtime.idlePhase() * PI2);
        float micro = (float) Math.sin(runtime.idlePhase() * PI2 * 3.0f + 0.6f);
        float life = idle * (1.0f + weakness * 0.65f);

        float knee = rad(2.8f + weakness * 3.2f) * life;
        float alternating = shift * rad(1.5f) * life;

        pose.bodyY += (0.018f + weakness * 0.020f) * (0.5f + slow * 0.5f) * life;
        pose.bodyZRot += shift * rad(0.42f) * life;
        pose.rightLegX += -0.025f * life + shift * 0.018f * life;
        pose.leftLegX += 0.025f * life + shift * 0.018f * life;
        pose.rightLegXRot += knee + alternating + micro * rad(0.35f) * life;
        pose.leftLegXRot += knee - alternating - micro * rad(0.30f) * life;
        pose.rightLegZRot -= rad(0.75f) * life + shift * rad(0.32f) * life;
        pose.leftLegZRot += rad(0.75f) * life - shift * rad(0.32f) * life;
        pose.rightKneeXRot += knee * 1.15f + Math.max(0.0f, shift) * rad(1.0f) * life;
        pose.leftKneeXRot += knee * 1.15f + Math.max(0.0f, -shift) * rad(1.0f) * life;
    }

    private void applyMovementStance(LegPose pose, float speed, float crouch) {
        float walk = clamp01(speed);
        if (walk > 0.01f) {
            float phase = runtime.weightShiftPhase() * PI2;
            float step = (float) Math.sin(phase);
            float kneeBase = rad(2.0f) * walk;
            pose.rightKneeXRot += kneeBase + Math.max(0.0f, step) * rad(4.5f) * walk;
            pose.leftKneeXRot += kneeBase + Math.max(0.0f, -step) * rad(4.5f) * walk;
            pose.rightLegZRot -= rad(0.55f) * walk;
            pose.leftLegZRot += rad(0.55f) * walk;
        }

        if (crouch > 0.0f) {
            pose.bodyY += 0.10f * crouch;
            pose.bodyXRot += rad(1.4f) * crouch;
            pose.rightLegXRot -= rad(3.6f) * crouch;
            pose.leftLegXRot -= rad(3.6f) * crouch;
            pose.rightKneeXRot += rad(8.0f) * crouch;
            pose.leftKneeXRot += rad(8.0f) * crouch;
        }
    }

    private void applyLandingCompression(LegPose pose, float landing, float weakness) {
        if (landing <= 0.01f) {
            return;
        }

        float compression = landing * (1.0f + weakness * 0.32f);
        pose.bodyY += compression * 0.34f;
        pose.bodyXRot += compression * rad(4.5f);
        pose.rightLegXRot -= compression * rad(8.5f);
        pose.leftLegXRot -= compression * rad(8.5f);
        pose.rightLegZRot -= compression * rad(2.0f);
        pose.leftLegZRot += compression * rad(2.0f);
        pose.rightKneeXRot += compression * rad(18.0f);
        pose.leftKneeXRot += compression * rad(18.0f);
    }

    private void applyPose(PlayerModel model, LegPose pose) {
        model.body.y += pose.bodyY;
        model.body.xRot += pose.bodyXRot;
        model.body.zRot += pose.bodyZRot;

        model.rightLeg.x += pose.rightLegX;
        model.rightLeg.y += pose.rightLegY;
        model.rightLeg.xRot += pose.rightLegXRot;
        model.rightLeg.yRot += pose.rightLegYRot;
        model.rightLeg.zRot += pose.rightLegZRot;

        model.leftLeg.x += pose.leftLegX;
        model.leftLeg.y += pose.leftLegY;
        model.leftLeg.xRot += pose.leftLegXRot;
        model.leftLeg.yRot += pose.leftLegYRot;
        model.leftLeg.zRot += pose.leftLegZRot;

        applyChildXRot(model.rightLeg, "oasis_knee_cartilage", pose.rightKneeXRot);
        applyChildXRot(model.leftLeg, "oasis_knee_cartilage", pose.leftKneeXRot);
        syncPants(model);
    }

    private void syncPants(PlayerModel model) {
        copyPartPose(model.rightLeg, model.rightPants);
        copyPartPose(model.leftLeg, model.leftPants);
        applyChildXRot(model.rightPants, "oasis_knee_cartilage", readChildXRot(model.rightLeg, "oasis_knee_cartilage"));
        applyChildXRot(model.leftPants, "oasis_knee_cartilage", readChildXRot(model.leftLeg, "oasis_knee_cartilage"));
    }

    private void copyPartPose(ModelPart source, ModelPart target) {
        if (source == null || target == null) {
            return;
        }
        target.x = source.x;
        target.y = source.y;
        target.z = source.z;
        target.xRot = source.xRot;
        target.yRot = source.yRot;
        target.zRot = source.zRot;
        target.visible = source.visible;
        target.skipDraw = source.skipDraw;
    }

    private void applyChildXRot(ModelPart parent, String child, float xRot) {
        try {
            parent.getChild(child).xRot += xRot;
        } catch (RuntimeException ignored) {
        }
    }

    private float readChildXRot(ModelPart parent, String child) {
        try {
            return parent.getChild(child).xRot;
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    private boolean hasChild(ModelPart parent, String child) {
        try {
            parent.getChild(child);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static float rad(float degrees) {
        return degrees * 0.017453292f;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
