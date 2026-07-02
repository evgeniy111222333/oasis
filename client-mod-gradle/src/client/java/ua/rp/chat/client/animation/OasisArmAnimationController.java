package ua.rp.chat.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;
import ua.rp.chat.client.vitals.VitalsClientState;

public final class OasisArmAnimationController {
    private static final OasisArmAnimationController INSTANCE = new OasisArmAnimationController();
    private static final float PI2 = (float) (Math.PI * 2.0);

    private final ArmRuntimeState runtime = new ArmRuntimeState();
    private final ArmAnimationDiagnostics diagnostics = new ArmAnimationDiagnostics();

    private OasisArmAnimationController() {
    }

    public static OasisArmAnimationController getInstance() {
        return INSTANCE;
    }

    public void clientTick(Minecraft client) {
        runtime.update(client);
    }

    public ArmAnimationDiagnostics.Snapshot lastDiagnostics() {
        return diagnostics.lastSnapshot();
    }

    public String lastDiagnosticsJson() {
        return diagnostics.lastSnapshotJson();
    }

    public void apply(PlayerModel model, AvatarRenderState state, Player player, boolean firstPersonBody) {
        Minecraft client = Minecraft.getInstance();
        if (model == null || state == null || player == null || client == null || client.player != player
                || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        if (!firstPersonBody) {
            ArmPose inactivePose = new ArmPose();
            diagnostics.capture(client, model, state, player, runtime, inactivePose, false, false, false);
            return;
        }

        ArmPose pose = buildPose(state, player, firstPersonBody);
        applyPose(model, pose);

        boolean rightForearmPresent = hasChild(model.rightArm, "oasis_forearm");
        boolean leftForearmPresent = hasChild(model.leftArm, "oasis_forearm");
        diagnostics.capture(client, model, state, player, runtime, pose, firstPersonBody, rightForearmPresent, leftForearmPresent);
    }

    private ArmPose buildPose(AvatarRenderState state, Player player, boolean firstPersonBody) {
        ArmPose pose = new ArmPose();
        ItemMotionProfile item = runtime.itemProfile();
        float speed = clamp01(Math.max(runtime.smoothedSpeed01(), state.walkAnimationSpeed * 2.8f));
        float sprint = "sprint".equals(runtime.movementMode()) ? 1.0f : 0.0f;
        float crouch = state.isCrouching ? 1.0f : 0.0f;
        float airborne = "airborne".equals(runtime.movementMode()) ? 1.0f : 0.0f;
        float calm = clamp01(1.0f - speed * 0.62f - sprint * 0.18f);
        float firstPersonGain = firstPersonBody ? 1.12f : 0.72f;

        float staminaLow = 1.0f - VitalsClientState.getStamina01();
        float breathDebt = clamp01(VitalsClientState.getBreathDebt() / 100.0f);
        float fatigue = clamp01(VitalsClientState.getFatigue() / 100.0f);
        float bloodLoss = 1.0f - VitalsClientState.getBlood01();
        float pain = clamp01(VitalsClientState.getPain() / 100.0f);
        float bleeding = clamp01(VitalsClientState.getBleeding() / 100.0f);
        float injury = clamp01(Math.max(Math.max(staminaLow * 0.55f, breathDebt * 0.76f),
                Math.max(fatigue * 0.70f, Math.max(bloodLoss * 0.82f, Math.max(pain * 0.78f, bleeding * 0.55f)))));

        applyBaseHumanCarry(pose, calm, crouch, firstPersonGain);
        applyBreathing(pose, calm, fatigue, breathDebt, injury, firstPersonGain);
        applyLocomotion(pose, speed, sprint, crouch, airborne, firstPersonGain);
        applyLookAndTurn(pose, state, firstPersonGain);
        applyItemProfile(pose, item, speed, firstPersonGain);
        applyHealthResponse(pose, injury, pain, bloodLoss, breathDebt, firstPersonGain);
        applyImpulses(pose, item, firstPersonGain);

        return pose;
    }

    private void applyBaseHumanCarry(ArmPose pose, float calm, float crouch, float gain) {
        float relaxed = calm * gain;
        pose.bodyXRot += rad(1.1f) * relaxed + rad(3.8f) * crouch;
        pose.rightArmXRot -= rad(4.4f) * relaxed;
        pose.leftArmXRot -= rad(4.4f) * relaxed;
        pose.rightArmZRot -= rad(2.2f) * relaxed;
        pose.leftArmZRot += rad(2.2f) * relaxed;
        pose.rightArmYRot -= rad(1.2f) * relaxed;
        pose.leftArmYRot += rad(1.2f) * relaxed;
        pose.rightForearmXRot += rad(8.0f) * relaxed;
        pose.leftForearmXRot += rad(8.0f) * relaxed;
    }

    private void applyBreathing(ArmPose pose, float calm, float fatigue, float breathDebt, float injury, float gain) {
        float breath = breathCurve(runtime.breathPhase());
        float secondary = (float) Math.sin((runtime.breathPhase() * PI2) + 1.15f);
        float amplitude = (0.55f + fatigue * 1.20f + breathDebt * 1.55f + injury * 0.72f) * calm * gain;
        float lift = (breath - 0.36f + secondary * 0.08f) * amplitude;

        pose.bodyXRot += lift * rad(1.55f);
        pose.bodyZRot += secondary * rad(0.35f) * calm;
        pose.rightArmXRot += lift * rad(2.6f);
        pose.leftArmXRot += lift * rad(2.6f);
        pose.rightArmZRot -= lift * rad(1.25f);
        pose.leftArmZRot += lift * rad(1.25f);
        pose.rightForearmXRot += lift * rad(3.4f);
        pose.leftForearmXRot += lift * rad(3.4f);
    }

    private void applyLocomotion(ArmPose pose, float speed, float sprint, float crouch, float airborne, float gain) {
        float phase = runtime.stridePhase() * PI2;
        float step = (float) Math.sin(phase);
        float step2 = (float) Math.sin(phase * 2.0f);
        float walk = speed * (1.0f - airborne) * gain;
        float runTuck = sprint * speed * gain;

        pose.bodyXRot += rad(1.8f) * walk + rad(4.2f) * runTuck;
        pose.bodyZRot += step * rad(1.1f) * walk;
        pose.rightArmXRot += step * rad(8.0f) * walk - rad(8.0f) * runTuck;
        pose.leftArmXRot -= step * rad(8.0f) * walk - rad(8.0f) * runTuck;
        pose.rightArmYRot += step2 * rad(1.6f) * walk;
        pose.leftArmYRot += step2 * rad(1.6f) * walk;
        pose.rightArmZRot -= Math.abs(step) * rad(2.2f) * walk;
        pose.leftArmZRot += Math.abs(step) * rad(2.2f) * walk;
        pose.rightForearmXRot += (0.20f + Math.max(0.0f, -step) * 0.45f) * rad(18.0f) * walk;
        pose.leftForearmXRot += (0.20f + Math.max(0.0f, step) * 0.45f) * rad(18.0f) * walk;

        if (crouch > 0.0f) {
            pose.rightArmXRot -= rad(5.5f) * crouch;
            pose.leftArmXRot -= rad(5.5f) * crouch;
            pose.rightForearmXRot += rad(7.0f) * crouch;
            pose.leftForearmXRot += rad(7.0f) * crouch;
        }

        if (airborne > 0.0f) {
            float airtime = clamp01(runtime.airborneTicks() / 10.0f);
            pose.rightArmXRot -= rad(4.0f + airtime * 4.0f) * gain;
            pose.leftArmXRot -= rad(4.0f + airtime * 4.0f) * gain;
            pose.rightArmZRot -= rad(3.0f) * gain;
            pose.leftArmZRot += rad(3.0f) * gain;
        }
    }

    private void applyLookAndTurn(ArmPose pose, AvatarRenderState state, float gain) {
        float lookDown = smoothStep(20.0f, 72.0f, clamp(state.xRot, 0.0f, 90.0f));
        pose.bodyXRot += lookDown * rad(2.8f) * gain;
        pose.rightArmXRot -= lookDown * rad(9.5f) * gain;
        pose.leftArmXRot -= lookDown * rad(9.5f) * gain;
        pose.rightForearmXRot += lookDown * rad(6.0f) * gain;
        pose.leftForearmXRot += lookDown * rad(6.0f) * gain;

        float yawLag = runtime.yawInertia();
        float pitchLag = runtime.pitchInertia();
        pose.bodyYRot -= yawLag * rad(2.2f) * gain;
        pose.rightArmYRot -= yawLag * rad(7.0f) * gain;
        pose.leftArmYRot -= yawLag * rad(5.2f) * gain;
        pose.rightArmXRot += pitchLag * rad(3.4f) * gain;
        pose.leftArmXRot += pitchLag * rad(2.2f) * gain;
    }

    private void applyItemProfile(ArmPose pose, ItemMotionProfile item, float speed, float gain) {
        float guard = item.guard() * (1.0f - Math.min(speed, 0.72f) * 0.45f) * gain;
        float weight = item.weight() * gain;
        float twoHanded = item.twoHanded() * gain;

        pose.bodyXRot += weight * rad(2.0f);
        pose.rightArmXRot -= weight * rad(5.8f);
        pose.rightForearmXRot += weight * rad(8.5f);

        if ("melee".equals(item.group()) || "heavy_melee".equals(item.group()) || "tool".equals(item.group())) {
            pose.bodyYRot += guard * rad(4.8f);
            pose.rightArmXRot -= guard * rad(7.5f);
            pose.rightArmYRot -= guard * rad(5.5f);
            pose.leftArmXRot -= twoHanded * rad(8.5f);
            pose.leftArmYRot += twoHanded * rad(7.5f);
            pose.leftForearmXRot += twoHanded * rad(12.0f);
        } else if ("ranged".equals(item.group())) {
            pose.bodyYRot += twoHanded * rad(5.0f);
            pose.leftArmXRot -= twoHanded * rad(18.0f);
            pose.leftArmYRot += twoHanded * rad(11.0f);
            pose.rightArmXRot -= twoHanded * rad(10.5f);
            pose.rightArmYRot -= twoHanded * rad(8.0f);
            pose.leftForearmXRot += twoHanded * rad(15.0f);
            pose.rightForearmXRot += twoHanded * rad(10.0f);
        } else if ("shield".equals(item.group())) {
            pose.leftArmXRot -= guard * rad(20.0f);
            pose.leftArmYRot += guard * rad(18.0f);
            pose.leftArmZRot += guard * rad(7.0f);
            pose.leftForearmXRot += guard * rad(16.0f);
        } else if ("medical".equals(item.group())) {
            float medical = item.medical() * gain;
            pose.bodyXRot += medical * rad(3.6f);
            pose.rightArmXRot -= medical * rad(19.0f);
            pose.rightArmYRot -= medical * rad(8.0f);
            pose.leftArmXRot -= medical * rad(16.0f);
            pose.leftArmYRot += medical * rad(9.0f);
            pose.rightForearmXRot += medical * rad(28.0f);
            pose.leftForearmXRot += medical * rad(24.0f);
        } else if ("consumable".equals(item.group())) {
            pose.rightArmXRot -= guard * rad(16.0f);
            pose.rightArmYRot -= guard * rad(7.0f);
            pose.rightForearmXRot += guard * rad(24.0f);
        }
    }

    private void applyHealthResponse(ArmPose pose, float injury, float pain, float bloodLoss, float breathDebt, float gain) {
        if (injury <= 0.02f) {
            return;
        }

        float tremor = ((float) Math.sin(runtime.idlePhase() * PI2 * 13.0f)
                + (float) Math.sin(runtime.idlePhase() * PI2 * 21.0f + 0.7f) * 0.45f) * injury * gain;
        float protect = Math.max(pain, bloodLoss * 0.75f) * gain;
        float airHunger = breathDebt * gain;

        pose.bodyXRot += injury * rad(3.5f);
        pose.rightArmXRot += injury * rad(4.5f);
        pose.leftArmXRot += injury * rad(4.5f);
        pose.rightArmZRot += tremor * rad(1.6f);
        pose.leftArmZRot -= tremor * rad(1.4f);
        pose.rightArmYRot += tremor * rad(1.1f);
        pose.leftArmYRot -= tremor * rad(1.0f);
        pose.leftArmXRot -= protect * rad(5.4f);
        pose.leftArmYRot += protect * rad(7.0f);
        pose.leftForearmXRot += protect * rad(10.0f);
        pose.rightForearmXRot += airHunger * rad(6.5f);
        pose.leftForearmXRot += airHunger * rad(6.5f);
    }

    private void applyImpulses(ArmPose pose, ItemMotionProfile item, float gain) {
        float action = runtime.actionImpulse() * gain;
        float land = runtime.landingImpulse() * gain;
        if (action > 0.01f) {
            float weighted = 0.72f + item.weight() * 0.55f;
            pose.rightArmXRot -= action * weighted * rad(8.0f);
            pose.rightArmYRot -= action * weighted * rad(5.0f);
            pose.rightForearmXRot += action * weighted * rad(12.0f);
            pose.bodyYRot += action * weighted * rad(1.8f);
        }
        if (land > 0.01f) {
            pose.bodyXRot += land * rad(5.8f);
            pose.rightArmXRot -= land * rad(8.0f);
            pose.leftArmXRot -= land * rad(8.0f);
            pose.rightForearmXRot += land * rad(10.0f);
            pose.leftForearmXRot += land * rad(10.0f);
        }
    }

    private void applyPose(PlayerModel model, ArmPose pose) {
        model.body.x += pose.bodyX;
        model.body.y += pose.bodyY;
        model.body.z += pose.bodyZ;
        model.body.xRot += pose.bodyXRot;
        model.body.yRot += pose.bodyYRot;
        model.body.zRot += pose.bodyZRot;

        model.rightArm.x += pose.rightArmX;
        model.rightArm.y += pose.rightArmY;
        model.rightArm.z += pose.rightArmZ;
        model.rightArm.xRot += pose.rightArmXRot;
        model.rightArm.yRot += pose.rightArmYRot;
        model.rightArm.zRot += pose.rightArmZRot;

        model.leftArm.x += pose.leftArmX;
        model.leftArm.y += pose.leftArmY;
        model.leftArm.z += pose.leftArmZ;
        model.leftArm.xRot += pose.leftArmXRot;
        model.leftArm.yRot += pose.leftArmYRot;
        model.leftArm.zRot += pose.leftArmZRot;

        applyForearm(model.rightArm, model.rightSleeve, pose.rightForearmXRot);
        applyForearm(model.leftArm, model.leftSleeve, pose.leftForearmXRot);
    }

    private void applyForearm(ModelPart arm, ModelPart sleeve, float xRot) {
        applyChildXRot(arm, "oasis_forearm", xRot);
        applyChildXRot(sleeve, "oasis_forearm_sleeve", xRot);
    }

    private void applyChildXRot(ModelPart parent, String child, float xRot) {
        try {
            parent.getChild(child).xRot += xRot;
        } catch (RuntimeException ignored) {
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

    private static float breathCurve(float phase) {
        if (phase < 0.34f) {
            return smoothStep(0.0f, 0.34f, phase);
        }
        if (phase < 0.88f) {
            return 1.0f - smoothStep(0.34f, 0.88f, phase);
        }
        return 0.0f;
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float x = clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    private static float rad(float degrees) {
        return degrees * 0.017453292f;
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
