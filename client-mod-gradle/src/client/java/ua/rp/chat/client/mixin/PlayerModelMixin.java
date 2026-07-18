package ua.rp.chat.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;
import java.util.Arrays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.AcquaintanceClientState;
import ua.rp.chat.RespirationModel;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.debug.EclipsePoseDebugExporter;
import ua.rp.chat.client.debug.HammerRenderQaController;
import ua.rp.chat.client.render.LocalPlayerRenderState;
import ua.rp.chat.client.render.BreathingPoseState;
import ua.rp.chat.client.render.BreathingTorsoRenderer;
import ua.rp.chat.client.render.ElbowBridgeRenderer;
import ua.rp.chat.ArticulatedLimbLayout;
import ua.rp.chat.BreathingShoulderLayout;
import ua.rp.chat.HeavyHammerAnimation;
import ua.rp.chat.HeavyHammerGait;
import ua.rp.chat.HeavyHammerItemTransform;
import ua.rp.chat.client.heavyhammer.HeavyHammerClientState;

@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Z)V", at = @At("RETURN"))
    private void eclipse$remapSegmentEndCaps(ModelPart root, boolean slim, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        eclipse$remapLowerSegment(model.rightArm, "eclipse_forearm", "eclipse_forearm_sleeve", true);
        eclipse$remapLowerSegment(model.leftArm, "eclipse_forearm", "eclipse_forearm_sleeve", true);
        eclipse$remapLowerSegment(model.rightLeg, "eclipse_shin", "eclipse_shin_pants", false);
        eclipse$remapLowerSegment(model.leftLeg, "eclipse_shin", "eclipse_shin_pants", false);
        eclipse$removeArmJointCaps(model.rightArm);
        eclipse$removeArmJointCaps(model.leftArm);
        eclipse$removeWearableEndCaps(model.rightArm, "eclipse_upper_arm", "eclipse_upper_sleeve", "eclipse_forearm", "eclipse_forearm_sleeve");
        eclipse$removeWearableEndCaps(model.leftArm, "eclipse_upper_arm", "eclipse_upper_sleeve", "eclipse_forearm", "eclipse_forearm_sleeve");
        eclipse$removeWearableEndCaps(model.rightLeg, "eclipse_thigh", "eclipse_thigh_pants", "eclipse_shin", "eclipse_shin_pants");
        eclipse$removeWearableEndCaps(model.leftLeg, "eclipse_thigh", "eclipse_thigh_pants", "eclipse_shin", "eclipse_shin_pants");
        int armWidth = slim ? 3 : 4;
        ElbowBridgeRenderer.register(
                eclipse$getChildOrNull(model.rightArm, "eclipse_elbow_bridge"),
                eclipse$getChildOrNull(model.rightArm, "eclipse_forearm"),
                model.rightSleeve, slim ? -2.0f : -3.0f, armWidth, 40, 16, 40, 32);
        ElbowBridgeRenderer.register(
                eclipse$getChildOrNull(model.leftArm, "eclipse_elbow_bridge"),
                eclipse$getChildOrNull(model.leftArm, "eclipse_forearm"),
                model.leftSleeve, -1.0f, armWidth, 32, 48, 48, 48);
        ModelPart torsoMarker = eclipse$getChildOrNull(model.body, "eclipse_breathing_torso");
        BreathingTorsoRenderer.register(torsoMarker, model.jacket);
        if (torsoMarker == null) {
            ua.rp.chat.client.EclipseClientMod.LOGGER.error(
                    "[TORSO] Dynamic breathing marker is missing; player torso cannot be rendered safely.");
        }
    }

    @Inject(
            method = "translateToHand(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("RETURN")
    )
    private void eclipse$followForearmWithHeldItem(
            AvatarRenderState state, HumanoidArm side, PoseStack poseStack, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        ModelPart arm = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        ModelPart forearm = eclipse$getChildOrNull(arm, "eclipse_forearm");
        if (forearm == null) {
            return;
        }

        // Vanilla positions the item for a straight 12px arm. Conjugating that
        // transform around the real 6px elbow makes the hand and held item share
        // one bone without changing vanilla placement when the bend is zero.
        forearm.translateAndRotate(poseStack);
        poseStack.translate(-forearm.x / 16.0f, -forearm.y / 16.0f, -forearm.z / 16.0f);

        if (side == HumanoidArm.RIGHT) {
            Player player = eclipse$getRenderedPlayer(state);
            HeavyHammerAnimation.Sample hammer = HeavyHammerClientState.poseFor(player, state.ageInTicks);
            if (hammer != null && player != null
                    && HeavyHammerClientState.isHolding(player.getMainHandItem())) {
                eclipse$orientProceduralHammer(poseStack, arm, forearm, hammer);
            }
        }
    }

    @Unique
    private void eclipse$orientProceduralHammer(
            PoseStack poseStack, ModelPart arm, ModelPart forearm, HeavyHammerAnimation.Sample hammer) {
        HeavyHammerItemTransform.Result transform = HeavyHammerItemTransform.solve(hammer,
                arm.xRot, arm.yRot, arm.zRot, forearm.xRot, forearm.yRot, forearm.zRot);
        poseStack.mulPose(transform.correction());
        poseStack.translate(transform.compensation().x,
                transform.compensation().y, transform.compensation().z);
    }

    @Inject(method = "createMesh(Lnet/minecraft/client/model/geom/builders/CubeDeformation;Z)Lnet/minecraft/client/model/geom/builders/MeshDefinition;", at = @At("RETURN"))
    private static void eclipse$createSegmentedMesh(CubeDeformation deformation, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
        MeshDefinition mesh = cir.getReturnValue();
        if (mesh == null) {
            return;
        }
        PartDefinition root = mesh.getRoot();
        eclipse$replaceArm(root, "right_arm", "right_sleeve", -5.0f, true, slim, 40, 16, 40, 32, deformation);
        eclipse$replaceArm(root, "left_arm", "left_sleeve", 5.0f, false, slim, 32, 48, 48, 48, deformation);
        eclipse$replaceLeg(root, "right_leg", "right_pants", -ArticulatedLimbLayout.LEG_HIP_X, 0, 16, 0, 32, deformation);
        eclipse$replaceLeg(root, "left_leg", "left_pants", ArticulatedLimbLayout.LEG_HIP_X, 16, 48, 0, 48, deformation);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        body.addOrReplaceChild("jacket", CubeListBuilder.create(), PartPose.ZERO);
        body.addOrReplaceChild("eclipse_breathing_torso", CubeListBuilder.create(), PartPose.ZERO);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("RETURN"))
    private void eclipse$afterSetupAnim(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        eclipse$resetModelTranslations(model, state);
        model.head.visible = true;
        model.head.skipDraw = false;
        model.hat.visible = state.showHat;
        model.hat.skipDraw = false;

        eclipse$applyStableRoleplayPose(model, state);
        boolean localFirstPerson = eclipse$isLocalFirstPersonState(state)
                && SmartCameraManager.getInstance().shouldApplyFirstPersonBodyPose();
        if (localFirstPerson) {
            SmartCameraManager.getInstance().applyFirstPersonBodyPose(model);
        }
        Player player = eclipse$getRenderedPlayer(state);
        eclipse$applyHeavyHammerPose(model, state, player);
        eclipse$applyPhysicalInteractionPose(model, state, player);
        if (localFirstPerson) {
            eclipse$anchorUpperBodyAtHips(model);
            eclipse$syncWearableLayers(model);
        }
        EclipsePoseDebugExporter.capture(model, state, player, eclipse$isLocalFirstPersonState(state));
    }

    @Unique
    private void eclipse$applyStableRoleplayPose(PlayerModel model, AvatarRenderState state) {
        ModelPart torsoMarker = model == null ? null : eclipse$getChildOrNull(model.body, "eclipse_breathing_torso");
        BreathingTorsoRenderer.update(torsoMarker, 0.0, 0.0, 0.0, false);
        if (state == null || model == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        BreathingPoseState.Sample breathState = BreathingPoseState.sample(state);
        float moving = breathState.moving();
        // НЕ повне згасання — реальні люди дихають i при ходьбі.
        // minCalm 0.28 guarantees visible breathing while preserving action poses.
        float calm = breathState.calm();

        // --- ДИХАННЯ ---
        // The shared controller keeps the local cycle continuous at 14-50 BPM.
        // body.y remains untouched so the neck seam cannot open during breathing.
        RespirationModel.Snapshot respiration = breathState.respiration();
        float respiratoryIntensity = (float) respiration.intensity();
        float breathGain = calm * (0.38f + respiratoryIntensity * 0.62f);
        float chestExpansion = (float) respiration.expansion() * breathGain;
        boolean localFirstPerson = breathState.firstPerson();
        BreathingTorsoRenderer.update(
                torsoMarker, respiration.phase(), respiratoryIntensity, calm, localFirstPerson);

        BreathingShoulderLayout.Pose shoulder = BreathingShoulderLayout.pose(
                respiration.phase(), respiratoryIntensity, calm, localFirstPerson);
        model.leftArm.x += shoulder.rootOutPixels();
        model.rightArm.x -= shoulder.rootOutPixels();
        model.leftArm.y -= shoulder.liftPixels();
        model.rightArm.y -= shoulder.liftPixels();

        // The surface itself expands in a bottom-up V. The skeleton only follows
        // enough to sell the rib-cage lift without throwing hands around.
        model.body.xRot += chestExpansion * (0.0160f + respiratoryIntensity * 0.0060f);
        model.leftArm.xRot += shoulder.forwardPitchRadians();
        model.rightArm.xRot += shoulder.forwardPitchRadians();
        model.leftArm.zRot -= shoulder.outwardRollRadians();
        model.rightArm.zRot += shoulder.outwardRollRadians();

        // Exhaustion is a slowly blended posture, never a second oscillator.
        float exhaustedPosture = eclipse$smoothStep(0.62f, 1.0f, respiratoryIntensity) * (1.0f - moving * 0.70f);
        model.body.xRot += exhaustedPosture * 0.018f;
        model.leftArm.xRot += exhaustedPosture * 0.025f;
        model.rightArm.xRot += exhaustedPosture * 0.025f;

        // Легкий ваго-перенос (вес тела переносится с ноги на ногу).
        float idleShift = (float) Math.sin(state.ageInTicks * 0.0105f + Math.sin(state.ageInTicks * 0.003f) * 0.4f) * calm * (1.0f - moving * 0.35f);
        model.body.zRot += idleShift * 0.005f;
        model.leftArm.zRot += idleShift * 0.003f;
        model.rightArm.zRot += idleShift * 0.003f;

        // --- НАХИЛ ПРИ ПОГЛЯДІ ВНИЗ ---
        // Поріг знижено з 58°→86° до 25°→65°: нахил починається раніше,
        // відчувається природніше (дивлюся на землю — тулуб нахиляється).
        // ВАЖЛИВО: НЕ зсуваємо body.y/body.z — це рвало модель.
        // Тільки оберт body.xRot (плечі й шия залишаються нерухомими
        // при оберті навколо X — точка кріплення голови (0,0,0) не змінюється).
        float lookDown = eclipse$smoothStep(25.0f, 65.0f, eclipse$clamp(state.xRot, 0.0f, 90.0f));
        float lean = lookDown * (state.isCrouching ? 0.5f : 1.0f);
        float torsoLeanDelta = lean * 0.060f;
        model.body.xRot += torsoLeanDelta;
        model.leftArm.xRot -= lean * 0.24f;
        model.rightArm.xRot -= lean * 0.24f;
        // Невеликий поворот плечей (в положенні "огляд" — тіло трохи розвертається).
        model.leftArm.yRot += lean * 0.04f;
        model.rightArm.yRot -= lean * 0.04f;

        // Голова наслідує 30% нахилу тулуба — шия гнеться, реалістично.
        // У першій особі head.visible=false, не впливає на вид гравця,
        // але інші гравці бачать, що голова не відірвана від тіла.
        model.head.xRot += torsoLeanDelta * 0.30f;

        // --- ПОВОРОТ ТУЛУБА ЗА ПОГЛЯДОМ ---
        float lookSide = eclipse$clamp(eclipse$wrapDegrees(state.yRot - state.bodyRot) / 90.0f, -1.0f, 1.0f);
        float upperTurn = lookSide * (0.025f + calm * 0.025f);
        model.body.yRot += upperTurn;
        model.leftArm.yRot += upperTurn * 0.45f;
        model.rightArm.yRot += upperTurn * 0.45f;

        eclipse$applyStableArmStance(model, state, moving, calm);
        eclipse$applyArticulatedLimbs(model, state, moving, calm, lean);
    }

    @Unique
    private void eclipse$applyStableArmStance(PlayerModel model, AvatarRenderState state, float moving, float calm) {
        model.leftArm.xRot -= calm * 0.025f;
        model.rightArm.xRot -= calm * 0.025f;
        model.leftArm.zRot += calm * 0.018f;
        model.rightArm.zRot -= calm * 0.018f;

        ItemStack main = state.getMainHandItemStack();
        String item = main == null || main.isEmpty() ? "" : main.getItem().toString().toLowerCase();
        boolean melee = item.contains("sword") || item.contains("axe") || item.contains("mace") || item.contains("trident") || item.contains("spear");
        boolean heavy = item.contains("axe") || item.contains("mace") || item.contains("hammer") || item.contains("great") || item.contains("halberd");
        boolean ranged = item.contains("bow") || item.contains("crossbow");

        if (melee) {
            float guard = 0.25f * (1.0f - Math.min(0.75f, moving));
            model.body.yRot += guard * 0.07f;
            model.leftArm.xRot -= guard * 0.10f;
            model.leftArm.yRot += guard * 0.12f;
            model.rightArm.xRot -= guard * 0.07f;
        }

        if (heavy) {
            float weight = 0.18f * (1.0f - Math.min(0.75f, moving));
            model.body.xRot += weight * 0.06f;
            model.rightArm.xRot -= weight * 0.12f;
            model.leftArm.xRot -= weight * 0.08f;
        }

        if (ranged && state.isUsingItem) {
            model.body.yRot += 0.12f;
            model.leftArm.xRot -= 0.16f;
            model.rightArm.xRot -= 0.12f;
            model.rightArm.yRot -= 0.12f;
        }
    }

    @Unique
    private void eclipse$applyPhysicalInteractionPose(PlayerModel model, AvatarRenderState state, Player player) {
        if (player == null || model == null || state == null || state.isFallFlying || state.isVisuallySwimming) {
            return;
        }
        AcquaintanceClientState.RoleplayPose pose = AcquaintanceClientState.poseFor(player);
        if (pose == AcquaintanceClientState.RoleplayPose.NONE) {
            return;
        }

        float progress = eclipse$clamp(pose.progress(), 0.0f, 1.0f);
        float ease = progress * progress * (3.0f - 2.0f * progress);
        float pulse = (float) Math.sin(state.ageInTicks * 0.38f) * 0.035f;

        if (pose.active()) {
            if (pose.actor()) {
                eclipse$applyActorInteractionPose(model, pose.action(), ease, pulse);
            } else {
                eclipse$applyTargetInteractionPose(model, pose.action(), ease, pulse);
            }
        }

        if (pose.carried()) {
            eclipse$applyEscortedPose(model, 1.0f, pulse);
        }
        if (pose.escorting()) {
            eclipse$applyEscortingPose(model, 1.0f, pulse);
        }
        if (pose.kneeling()) {
            eclipse$applyKneelingPose(model, 1.0f, pulse);
        }
        if (pose.bound()) {
            eclipse$applyBoundHandsPose(model, 1.0f, pulse);
        }
        eclipse$syncWearableLayers(model);
    }

    @Unique
    private void eclipse$applyActorInteractionPose(PlayerModel model, String action, float ease, float pulse) {
        if (action.equals("ESCAPE_HELP_ACTOR")) {
            model.body.xRot += 0.38f * ease;
            model.head.xRot += 0.24f * ease;
            model.leftArm.xRot = eclipse$lerp(model.leftArm.xRot, -0.92f, ease);
            model.rightArm.xRot = eclipse$lerp(model.rightArm.xRot, -0.86f, ease);
            model.leftArm.yRot -= 0.32f * ease;
            model.rightArm.yRot += 0.32f * ease;
            eclipse$setLowerArm(model.leftArm, model.leftSleeve, 0.52f + pulse);
            eclipse$setLowerArm(model.rightArm, model.rightSleeve, 0.48f - pulse);
            return;
        }
        boolean search = action.startsWith("SEARCH") || action.equals("INSPECT");
        boolean bind = action.equals("BIND");
        boolean disarm = action.equals("DISARM");
        boolean carry = action.equals("CARRY");
        boolean kneel = action.equals("KNEEL");

        float lean = (search ? 0.22f : bind ? 0.18f : disarm ? 0.14f : carry ? 0.16f : kneel ? 0.16f : 0.10f) * ease;
        eclipse$applyCoherentUpperLean(model, lean);
        model.leftLeg.zRot += 0.05f * ease;
        model.rightLeg.zRot -= 0.05f * ease;

        if (search || bind) {
            float work = 0.18f + Math.abs(pulse) * 1.7f;
            model.leftArm.xRot -= (0.78f + work) * ease;
            model.rightArm.xRot -= (0.72f + work * 0.85f) * ease;
            model.leftArm.yRot += (0.22f + pulse) * ease;
            model.rightArm.yRot -= (0.22f - pulse) * ease;
            model.leftArm.zRot += 0.10f * ease;
            model.rightArm.zRot -= 0.10f * ease;
        } else if (disarm) {
            model.rightArm.xRot -= (0.95f + pulse) * ease;
            model.rightArm.yRot -= 0.34f * ease;
            model.leftArm.xRot -= 0.34f * ease;
        } else if (carry) {
            eclipse$applyEscortingPose(model, ease, pulse);
        } else if (kneel) {
            model.rightArm.xRot -= 0.82f * ease;
            model.rightArm.yRot -= 0.25f * ease;
            model.leftArm.xRot -= 0.22f * ease;
        }
    }

    @Unique
    private void eclipse$applyTargetInteractionPose(PlayerModel model, String action, float ease, float pulse) {
        if (action.startsWith("ESCAPE_")) {
            eclipse$applyBoundHandsPose(model, ease, pulse);
            switch (action) {
                case "ESCAPE_STRUGGLE" -> {
                    model.body.xRot += (0.11f + Math.abs(pulse) * 0.7f) * ease;
                    model.body.zRot += pulse * 0.42f;
                    model.leftArm.yRot -= (0.16f + pulse) * ease;
                    model.rightArm.yRot += (0.16f - pulse) * ease;
                    model.leftLeg.zRot += 0.045f * ease;
                    model.rightLeg.zRot -= 0.045f * ease;
                }
                case "ESCAPE_BLADE" -> {
                    model.body.yRot += 0.10f * ease;
                    model.leftArm.xRot += (0.13f + pulse) * ease;
                    model.rightArm.xRot += (0.09f - pulse) * ease;
                    eclipse$setLowerArm(model.leftArm, model.leftSleeve, 0.56f + pulse);
                    eclipse$setLowerArm(model.rightArm, model.rightSleeve, 0.46f - pulse);
                }
                case "ESCAPE_STONE" -> {
                    model.body.xRot += 0.20f * ease;
                    model.body.yRot += (0.18f + pulse * 1.4f) * ease;
                    model.leftLeg.xRot -= 0.08f * ease;
                    model.rightLeg.xRot += 0.05f * ease;
                }
                case "ESCAPE_FIRE" -> {
                    model.body.xRot -= 0.08f * ease;
                    model.leftArm.xRot = eclipse$lerp(model.leftArm.xRot, -0.72f + pulse, ease);
                    model.rightArm.xRot = eclipse$lerp(model.rightArm.xRot, -0.72f - pulse, ease);
                    model.leftArm.yRot = eclipse$lerp(model.leftArm.yRot, -0.22f, ease);
                    model.rightArm.yRot = eclipse$lerp(model.rightArm.yRot, 0.22f, ease);
                }
                case "ESCAPE_HELP_TARGET" -> model.body.xRot += 0.08f * ease;
                default -> {
                }
            }
            return;
        }
        if (action.equals("BIND") || action.startsWith("SEARCH") || action.equals("DISARM")) {
            eclipse$applyBoundHandsPose(model, Math.min(1.0f, 0.35f + ease * 0.65f), pulse);
            model.body.xRot += 0.08f * ease;
        }
        if (action.equals("KNEEL")) {
            eclipse$applyKneelingPose(model, ease, pulse);
        }
        if (action.equals("CARRY")) {
            eclipse$applyEscortedPose(model, ease, pulse);
        }
    }

    @Unique
    private void eclipse$applyCoherentUpperLean(PlayerModel model, float lean) {
        model.body.xRot += lean;
        model.head.xRot -= lean * 0.35f;
        model.leftArm.xRot += lean * 0.35f;
        model.rightArm.xRot += lean * 0.35f;
    }

    @Unique
    private void eclipse$applyEscortingPose(PlayerModel model, float amount, float pulse) {
        model.body.yRot += 0.08f * amount;
        model.body.xRot += 0.06f * amount;
        model.rightArm.xRot -= (0.38f + pulse) * amount;
        model.rightArm.yRot += 0.58f * amount;
        model.rightArm.zRot -= 0.18f * amount;
        model.leftArm.xRot -= 0.10f * amount;
        model.leftLeg.zRot += 0.025f * amount;
        model.rightLeg.zRot -= 0.025f * amount;
    }

    @Unique
    private void eclipse$applyEscortedPose(PlayerModel model, float amount, float pulse) {
        eclipse$applyBoundHandsPose(model, amount, pulse);
        model.body.xRot += 0.05f * amount;
        model.body.yRot -= 0.04f * amount;
        model.leftLeg.xRot += 0.04f * amount;
        model.rightLeg.xRot += 0.03f * amount;
    }

    @Unique
    private void eclipse$applyBoundHandsPose(PlayerModel model, float amount, float pulse) {
        model.leftArm.xRot = model.leftArm.xRot * (1.0f - amount) + (0.70f + pulse) * amount;
        model.rightArm.xRot = model.rightArm.xRot * (1.0f - amount) + (0.70f - pulse) * amount;
        model.leftArm.yRot = model.leftArm.yRot * (1.0f - amount) + (-0.72f) * amount;
        model.rightArm.yRot = model.rightArm.yRot * (1.0f - amount) + (0.72f) * amount;
        model.leftArm.zRot = model.leftArm.zRot * (1.0f - amount) + (0.18f) * amount;
        model.rightArm.zRot = model.rightArm.zRot * (1.0f - amount) + (-0.18f) * amount;
        eclipse$setLowerArm(model.leftArm, model.leftSleeve, 0.34f * amount);
        eclipse$setLowerArm(model.rightArm, model.rightSleeve, 0.34f * amount);
    }

    @Unique
    private void eclipse$applyKneelingPose(PlayerModel model, float amount, float pulse) {
        float upperDrop = 5.5f * amount;
        float hipDrop = 3.0f * amount;

        model.head.y += upperDrop;
        model.body.y += upperDrop;
        model.leftArm.y += upperDrop;
        model.rightArm.y += upperDrop;
        model.leftLeg.y += hipDrop;
        model.rightLeg.y += hipDrop;

        model.body.xRot += 0.17f * amount;
        model.head.xRot -= 0.13f * amount;
        model.leftLeg.xRot = eclipse$lerp(model.leftLeg.xRot, -1.27f, amount);
        model.rightLeg.xRot = eclipse$lerp(model.rightLeg.xRot, -1.19f, amount);
        model.leftLeg.yRot = eclipse$lerp(model.leftLeg.yRot, 0.10f, amount);
        model.rightLeg.yRot = eclipse$lerp(model.rightLeg.yRot, -0.10f, amount);
        model.leftLeg.zRot = eclipse$lerp(model.leftLeg.zRot, 0.055f, amount);
        model.rightLeg.zRot = eclipse$lerp(model.rightLeg.zRot, -0.055f, amount);
        eclipse$setLowerLeg(model.leftLeg, model.leftPants, 1.30f * amount);
        eclipse$setLowerLeg(model.rightLeg, model.rightPants, 1.23f * amount);

        model.leftArm.xRot += (0.20f + pulse) * amount;
        model.rightArm.xRot += (0.20f - pulse) * amount;
        model.leftArm.yRot -= 0.06f * amount;
        model.rightArm.yRot += 0.06f * amount;
        model.leftArm.zRot += 0.075f * amount;
        model.rightArm.zRot -= 0.075f * amount;
    }

    @Unique
    private static void eclipse$replaceArm(PartDefinition root, String armName, String sleeveName, float x, boolean right, boolean slim, int texX, int texY, int sleeveTexX, int sleeveTexY, CubeDeformation deformation) {
        int width = slim ? 3 : 4;
        float minX = right ? (slim ? -2.0f : -3.0f) : -1.0f;
        PartDefinition arm = root.addOrReplaceChild(armName, CubeListBuilder.create(), PartPose.offset(x, 2.0f, 0.0f));

        // The elbow is a real bone boundary: the two boxes share one plane and never overlap.
        PartDefinition upperArm = arm.addOrReplaceChild("eclipse_upper_arm", 
                CubeListBuilder.create()
                        .texOffs(texX, texY).addBox(minX, ArticulatedLimbLayout.ARM_TOP_Y, -2.0f,
                                width, ArticulatedLimbLayout.armUpperHeight(), 4, deformation),
                PartPose.ZERO);

        PartDefinition forearm = arm.addOrReplaceChild("eclipse_forearm", 
                CubeListBuilder.create().texOffs(texX, texY + ArticulatedLimbLayout.LOWER_SEGMENT_TEXTURE_ROW_OFFSET)
                        .addBox(minX, ArticulatedLimbLayout.ARM_LOWER_LOCAL_TOP_Y, -2.0f,
                                width, ArticulatedLimbLayout.armLowerHeight(), 4, deformation),
                PartPose.offset(0.0f, ArticulatedLimbLayout.ARM_ELBOW_Y, 0.0f));

        // Grow the wearable only across the arm. Y growth would make both sleeve segments intersect.
        CubeDeformation sleeve = deformation.extend(
                ArticulatedLimbLayout.OUTER_LAYER_GROW_XZ,
                ArticulatedLimbLayout.OUTER_LAYER_GROW_Y,
                ArticulatedLimbLayout.OUTER_LAYER_GROW_XZ);

        upperArm.addOrReplaceChild("eclipse_upper_sleeve", 
                CubeListBuilder.create()
                        .texOffs(sleeveTexX, sleeveTexY).addBox(minX, ArticulatedLimbLayout.ARM_TOP_Y, -2.0f,
                                width, ArticulatedLimbLayout.armUpperHeight(), 4, sleeve),
                PartPose.ZERO);

        forearm.addOrReplaceChild("eclipse_forearm_sleeve", 
                CubeListBuilder.create().texOffs(sleeveTexX, sleeveTexY + ArticulatedLimbLayout.LOWER_SEGMENT_TEXTURE_ROW_OFFSET)
                        .addBox(minX, ArticulatedLimbLayout.ARM_LOWER_LOCAL_TOP_Y, -2.0f,
                                width, ArticulatedLimbLayout.armLowerHeight(), 4, sleeve),
                PartPose.ZERO);

        arm.addOrReplaceChild("eclipse_elbow_bridge", CubeListBuilder.create(), PartPose.ZERO);

        arm.addOrReplaceChild(sleeveName, CubeListBuilder.create(), PartPose.ZERO);
    }

    @Unique
    private static void eclipse$replaceLeg(PartDefinition root, String legName, String pantsName, float x, int texX, int texY, int pantsTexX, int pantsTexY, CubeDeformation deformation) {
        PartDefinition leg = root.addOrReplaceChild(legName, CubeListBuilder.create(), PartPose.offset(x, 12.0f, 0.0f));
        
        PartDefinition thigh = leg.addOrReplaceChild("eclipse_thigh", 
                CubeListBuilder.create()
                        .texOffs(texX, texY).addBox(-2.0f, ArticulatedLimbLayout.LEG_TOP_Y, -2.0f,
                                ArticulatedLimbLayout.LEG_WIDTH, ArticulatedLimbLayout.legUpperHeight(), 4, deformation),
                PartPose.ZERO);

        PartDefinition shin = leg.addOrReplaceChild("eclipse_shin", 
                CubeListBuilder.create().texOffs(texX, texY + ArticulatedLimbLayout.LOWER_SEGMENT_TEXTURE_ROW_OFFSET)
                        .addBox(-2.0f, ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y, -2.0f,
                                ArticulatedLimbLayout.LEG_WIDTH, ArticulatedLimbLayout.legLowerHeight(), 4, deformation),
                PartPose.offset(0.0f, ArticulatedLimbLayout.LEG_KNEE_Y, 0.0f));

        CubeDeformation pants = deformation.extend(
                ArticulatedLimbLayout.PANTS_LAYER_GROW_X,
                ArticulatedLimbLayout.OUTER_LAYER_GROW_Y,
                ArticulatedLimbLayout.PANTS_LAYER_GROW_Z);

        thigh.addOrReplaceChild("eclipse_thigh_pants", 
                CubeListBuilder.create()
                        .texOffs(pantsTexX, pantsTexY).addBox(-2.0f, ArticulatedLimbLayout.LEG_TOP_Y, -2.0f,
                                ArticulatedLimbLayout.LEG_WIDTH, ArticulatedLimbLayout.legUpperHeight(), 4, pants),
                PartPose.ZERO);

        shin.addOrReplaceChild("eclipse_shin_pants", 
                CubeListBuilder.create().texOffs(pantsTexX, pantsTexY + ArticulatedLimbLayout.LOWER_SEGMENT_TEXTURE_ROW_OFFSET)
                        .addBox(-2.0f, ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y, -2.0f,
                                ArticulatedLimbLayout.LEG_WIDTH, ArticulatedLimbLayout.legLowerHeight(), 4, pants),
                PartPose.ZERO);
        
        leg.addOrReplaceChild(pantsName, CubeListBuilder.create(), PartPose.ZERO);
    }

    @Unique
    private void eclipse$applyHeavyHammerPose(PlayerModel model, AvatarRenderState state, Player player) {
        HeavyHammerAnimation.Sample pose = HeavyHammerClientState.poseFor(player, state.ageInTicks);
        if (pose == null) return;

        float poseWeight = eclipse$clamp(pose.poseWeight(), 0.0f, 1.0f);
        float leftHandWeight = poseWeight * eclipse$clamp(pose.offhandWeight(), 0.0f, 1.0f);

        // IK решается от фиксированных плеч. Дыхание не должно сдвигать корни рук уже после решения,
        // иначе визуальная ладонь и рассчитанная точка хвата оказываются в разных системах координат.
        model.rightArm.x = eclipse$lerp(model.rightArm.x, HeavyHammerAnimation.RIGHT_SHOULDER.x(), poseWeight);
        model.rightArm.y = eclipse$lerp(model.rightArm.y, HeavyHammerAnimation.RIGHT_SHOULDER.y(), poseWeight);
        model.rightArm.z = eclipse$lerp(model.rightArm.z, HeavyHammerAnimation.RIGHT_SHOULDER.z(), poseWeight);
        model.leftArm.x = eclipse$lerp(model.leftArm.x, HeavyHammerAnimation.LEFT_SHOULDER.x(), leftHandWeight);
        model.leftArm.y = eclipse$lerp(model.leftArm.y, HeavyHammerAnimation.LEFT_SHOULDER.y(), leftHandWeight);
        model.leftArm.z = eclipse$lerp(model.leftArm.z, HeavyHammerAnimation.LEFT_SHOULDER.z(), leftHandWeight);

        // Сначала движется жёсткий молот, затем обе руки решаются к его реальным
        // точкам хвата. Корпус и ноги переносят массу по той же фазе, поэтому
        // удар не выглядит как вращение неподвижной стойки с приклеенными руками.
        model.body.xRot += pose.bodyX() * poseWeight;
        model.body.yRot += pose.bodyY() * poseWeight;
        model.body.zRot += pose.bodyZ() * poseWeight;
        model.head.xRot += pose.headX() * poseWeight;
        model.head.yRot += pose.headY() * poseWeight;
        model.rightArm.xRot = eclipse$lerp(model.rightArm.xRot, pose.rightX(), poseWeight);
        model.rightArm.yRot = eclipse$lerp(model.rightArm.yRot, pose.rightY(), poseWeight);
        model.rightArm.zRot = eclipse$lerp(model.rightArm.zRot, pose.rightZ(), poseWeight);
        model.leftArm.xRot = eclipse$lerp(model.leftArm.xRot, pose.leftX(), leftHandWeight);
        model.leftArm.yRot = eclipse$lerp(model.leftArm.yRot, pose.leftY(), leftHandWeight);
        model.leftArm.zRot = eclipse$lerp(model.leftArm.zRot, pose.leftZ(), leftHandWeight);
        eclipse$blendHammerLowerArm(model.rightArm, pose.rightLower(), pose.rightWristTwist(), poseWeight);
        eclipse$blendHammerLowerArm(model.leftArm, pose.leftLower(), pose.leftWristTwist(), leftHandWeight);

        // Широкая стойка создаётся наклоном ног, а не разрывом тазобедренных шарниров.
        float carryWeight = poseWeight * eclipse$clamp(pose.gaitWeight(), 0.0f, 1.0f);
        float actionWeight = poseWeight * (1.0f - eclipse$clamp(pose.gaitWeight(), 0.0f, 1.0f));
        if (carryWeight > 0.0f) {
            HammerRenderQaController.GaitRig qaGait = HammerRenderQaController.gaitRig();
            HeavyHammerGait.Sample gait = qaGait == null
                    ? HeavyHammerGait.sample(state.walkAnimationPos, state.walkAnimationSpeed,
                    state.speedValue, state.isCrouching)
                    : HeavyHammerGait.sample(qaGait.walkPosition(), qaGait.walkSpeed(),
                    qaGait.linearSpeed(), false);
            float stanceRoll = ArticulatedLimbLayout.stanceRoll(gait.stanceOffset());
            model.rightLeg.xRot = eclipse$lerp(model.rightLeg.xRot, gait.rightHipPitch(), carryWeight);
            model.leftLeg.xRot = eclipse$lerp(model.leftLeg.xRot, gait.leftHipPitch(), carryWeight);
            model.rightLeg.zRot = eclipse$lerp(model.rightLeg.zRot,
                    gait.rightHipRoll() + stanceRoll, carryWeight);
            model.leftLeg.zRot = eclipse$lerp(model.leftLeg.zRot,
                    gait.leftHipRoll() - stanceRoll, carryWeight);
            eclipse$blendLowerLeg(model.rightLeg, gait.rightKnee(), carryWeight);
            eclipse$blendLowerLeg(model.leftLeg, gait.leftKnee(), carryWeight);
            model.body.xRot += gait.torsoPitch() * carryWeight;
            model.body.yRot += gait.torsoYaw() * carryWeight;
            model.body.zRot += gait.torsoRoll() * carryWeight;
        }
        if (actionWeight > 0.0f) {
            float stanceRoll = ArticulatedLimbLayout.stanceRoll(pose.stanceWidth());
            model.rightLeg.xRot += pose.rightLegX() * actionWeight;
            model.leftLeg.xRot += pose.leftLegX() * actionWeight;
            model.rightLeg.zRot += (pose.rightLegZ() + stanceRoll) * actionWeight;
            model.leftLeg.zRot += (pose.leftLegZ() - stanceRoll) * actionWeight;
            eclipse$blendLowerLeg(model.rightLeg, pose.rightKnee(), actionWeight);
            eclipse$blendLowerLeg(model.leftLeg, pose.leftKnee(), actionWeight);
        }
        eclipse$syncWearableLayers(model);
    }

    @Unique
    private void eclipse$remapLowerSegment(
            ModelPart limb, String lowerName, String wearableName, boolean deriveCapsFromSideTexture) {
        ModelPart lower = eclipse$getChildOrNull(limb, lowerName);
        if (lower == null) {
            return;
        }
        int topShift = deriveCapsFromSideTexture
                ? ArticulatedLimbLayout.HAND_TOP_CAP_V_SHIFT_PIXELS
                : ArticulatedLimbLayout.ORIGINAL_CAP_V_SHIFT_PIXELS;
        int bottomShift = deriveCapsFromSideTexture
                ? ArticulatedLimbLayout.HAND_BOTTOM_CAP_V_SHIFT_PIXELS
                : ArticulatedLimbLayout.ORIGINAL_CAP_V_SHIFT_PIXELS;
        eclipse$remapEndCaps(lower, topShift, bottomShift);
        // Wearable caps are removed separately: drawing them in the same Y plane
        // as the base layer is the source of distance-dependent z-fighting.
    }

    @Unique
    private void eclipse$remapEndCaps(ModelPart part, int topShiftPixels, int bottomShiftPixels) {
        if (part == null) {
            return;
        }
        for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).getCubes()) {
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (ModelPart.Polygon polygon : cube.polygons) {
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    minY = Math.min(minY, vertex.y());
                    maxY = Math.max(maxY, vertex.y());
                }
            }
            for (ModelPart.Polygon polygon : cube.polygons) {
                float averageY = 0.0f;
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    averageY += vertex.y();
                }
                averageY /= polygon.vertices().length;
                boolean isTopCap = Math.abs(averageY - minY) <= 0.001f;
                boolean isBottomCap = Math.abs(averageY - maxY) <= 0.001f;
                if (isTopCap || isBottomCap) {
                    float shift = ArticulatedLimbLayout.normalizedVShift(
                            isTopCap ? topShiftPixels : bottomShiftPixels);
                    for (int i = 0; i < polygon.vertices().length; i++) {
                        ModelPart.Vertex vertex = polygon.vertices()[i];
                        polygon.vertices()[i] = vertex.remap(vertex.u(), vertex.v() + shift);
                    }
                }
            }
        }
    }

    @Unique
    private void eclipse$removeWearableEndCaps(
            ModelPart limb, String upperName, String upperWearable,
            String lowerName, String lowerWearable) {
        eclipse$removeEndCaps(eclipse$getChildOrNull(eclipse$getChildOrNull(limb, upperName), upperWearable));
        eclipse$removeEndCaps(eclipse$getChildOrNull(eclipse$getChildOrNull(limb, lowerName), lowerWearable));
    }

    @Unique
    private void eclipse$removeArmJointCaps(ModelPart arm) {
        eclipse$removeSelectedEndCaps(eclipse$getChildOrNull(arm, "eclipse_upper_arm"), false, true);
        eclipse$removeSelectedEndCaps(eclipse$getChildOrNull(arm, "eclipse_forearm"), true, false);
    }

    @Unique
    private void eclipse$removeEndCaps(ModelPart part) {
        eclipse$removeSelectedEndCaps(part, true, true);
    }

    @Unique
    private void eclipse$removeSelectedEndCaps(ModelPart part, boolean removeTop, boolean removeBottom) {
        if (part == null) {
            return;
        }
        for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).getCubes()) {
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (ModelPart.Polygon polygon : cube.polygons) {
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    minY = Math.min(minY, vertex.y());
                    maxY = Math.max(maxY, vertex.y());
                }
            }
            final float lower = minY;
            final float upper = maxY;
            ModelPart.Polygon[] sidePolygons = Arrays.stream(cube.polygons)
                    .filter(polygon -> {
                        float averageY = 0.0f;
                        for (ModelPart.Vertex vertex : polygon.vertices()) {
                            averageY += vertex.y();
                        }
                        averageY /= polygon.vertices().length;
                        boolean topCap = Math.abs(averageY - lower) <= 0.001f;
                        boolean bottomCap = Math.abs(averageY - upper) <= 0.001f;
                        return !(removeTop && topCap) && !(removeBottom && bottomCap);
                    })
                    .toArray(ModelPart.Polygon[]::new);
            ((ModelPartCubeAccessor) (Object) cube).eclipse$setPolygons(sidePolygons);
        }
    }

    @Unique
    private boolean eclipse$isLocalFirstPersonState(EntityRenderState state) {
        if (state instanceof LocalPlayerRenderState lprs && lprs.eclipse$isLocalPlayer()) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        return state instanceof AvatarRenderState avatar
                && client != null
                && client.player != null
                && avatar.id == client.player.getId();
    }

    @Unique
    private float eclipse$smoothStep(float edge0, float edge1, float value) {
        float x = eclipse$clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    @Unique
    private float eclipse$clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Unique
    private Player eclipse$getRenderedPlayer(AvatarRenderState state) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || state == null) {
            return null;
        }
        return client.level.getEntity(state.id) instanceof Player player ? player : null;
    }

    @Unique
    private float eclipse$getTerrainBalance(Player player) {
        if (player == null || player.level() == null || !player.onGround()) {
            return 0.0f;
        }
        double yaw = Math.toRadians(player.getYRot());
        double sideX = Math.cos(yaw) * 0.24;
        double sideZ = Math.sin(yaw) * 0.24;
        double left = eclipse$getSupportHeight(player, sideX, sideZ);
        double right = eclipse$getSupportHeight(player, -sideX, -sideZ);
        return eclipse$clamp((float) (left - right), -0.55f, 0.55f);
    }

    @Unique
    private double eclipse$getSupportHeight(Player player, double offsetX, double offsetZ) {
        double x = player.getX() + offsetX;
        double z = player.getZ() + offsetZ;
        int baseY = (int) Math.floor(player.getY() - 0.08);
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos pos = BlockPos.containing(x, baseY + dy, z);
            VoxelShape shape = player.level().getBlockState(pos).getCollisionShape(player.level(), pos);
            if (!shape.isEmpty()) {
                return pos.getY() + shape.max(Direction.Axis.Y);
            }
        }
        return player.getY();
    }

    @Unique
    private void eclipse$applyWeaponStance(PlayerModel model, AvatarRenderState state, float moving) {
        ItemStack main = state.getMainHandItemStack();
        String item = main == null || main.isEmpty() ? "" : main.getItem().toString().toLowerCase();
        boolean melee = item.contains("sword") || item.contains("axe") || item.contains("mace") || item.contains("trident") || item.contains("spear");
        boolean heavy = item.contains("axe") || item.contains("mace") || item.contains("hammer") || item.contains("great") || item.contains("halberd");
        boolean ranged = item.contains("bow") || item.contains("crossbow");

        if (melee) {
            float guard = 0.45f * (1.0f - Math.min(0.65f, moving));
            model.body.yRot += guard * 0.10f;
            model.body.xRot += guard * 0.035f;
            model.leftArm.xRot -= guard * 0.16f;
            model.leftArm.yRot += guard * 0.18f;
            model.rightArm.xRot -= guard * 0.08f;
            model.leftLeg.zRot += guard * 0.05f;
            model.rightLeg.zRot -= guard * 0.05f;
            model.leftLeg.xRot -= guard * 0.035f;
            model.rightLeg.xRot -= guard * 0.035f;
        }

        if (heavy) {
            float weight = 0.32f * (1.0f - Math.min(0.7f, moving));
            model.body.xRot += weight * 0.11f;
            model.rightArm.xRot -= weight * 0.24f;
            model.leftArm.xRot -= weight * 0.18f;
            model.leftArm.yRot += weight * 0.12f;
        }

        if (ranged && state.isUsingItem) {
            model.body.yRot += 0.22f;
            model.leftArm.xRot -= 0.26f;
            model.rightArm.xRot -= 0.18f;
            model.rightArm.yRot -= 0.22f;
            model.leftLeg.zRot += 0.07f;
            model.rightLeg.zRot -= 0.07f;
        }
    }

    @Unique
    private void eclipse$applyWeatherPosture(PlayerModel model, Player player, float calm) {
        if (player == null || player.level() == null || calm <= 0.0f || !player.level().isRainingAt(player.blockPosition())) {
            return;
        }
        float rain = 0.18f * calm;
        model.body.xRot += rain * 0.14f;
        model.leftArm.xRot -= rain * 0.28f;
        model.rightArm.xRot -= rain * 0.28f;
        model.leftArm.yRot += rain * 0.18f;
        model.rightArm.yRot -= rain * 0.18f;
    }

    @Unique
    private void eclipse$applyArticulatedLimbs(PlayerModel model, AvatarRenderState state, float moving, float calm, float lookDownLean) {
        float step = (float) Math.sin(state.walkAnimationPos * 0.6662f);
        float oppositeStep = (float) Math.sin(state.walkAnimationPos * 0.6662f + Math.PI);
        float walk = eclipse$clamp(moving, 0.0f, 1.0f);
        float idleArmBend = 0.32f * calm;
        float lookBend = 0.24f * lookDownLean;
        float runTuck = walk * (state.speedValue > 0.12f ? 0.08f : 0.0f);

        eclipse$setLowerArm(model.rightArm, model.rightSleeve, idleArmBend + lookBend + runTuck + walk * (0.13f + Math.max(0.0f, -step) * 0.22f));
        eclipse$setLowerArm(model.leftArm, model.leftSleeve, idleArmBend + lookBend + runTuck + walk * (0.13f + Math.max(0.0f, -oppositeStep) * 0.22f));

        float rightKnee = 0.13f * calm + walk * (0.10f + Math.max(0.0f, step) * 0.38f);
        float leftKnee = 0.13f * calm + walk * (0.10f + Math.max(0.0f, oppositeStep) * 0.38f);

        // Apply thigh compensation for standing/walking knee bend
        model.rightLeg.xRot -= rightKnee * 0.5f;
        model.leftLeg.xRot -= leftKnee * 0.5f;

        if (state.isCrouching) {
            rightKnee += 0.22f;
            leftKnee += 0.22f;
        }

        eclipse$setLowerLeg(model.rightLeg, model.rightPants, rightKnee);
        eclipse$setLowerLeg(model.leftLeg, model.leftPants, leftKnee);

        eclipse$syncWearableLayers(model);
    }

    @Unique
    private void eclipse$syncWearableLayers(PlayerModel model) {
        eclipse$resetWearableLocalPose(model.hat);
        eclipse$resetWearableLocalPose(model.jacket);
        eclipse$resetWearableLocalPose(model.rightSleeve);
        eclipse$resetWearableLocalPose(model.leftSleeve);
        eclipse$resetWearableLocalPose(model.rightPants);
        eclipse$resetWearableLocalPose(model.leftPants);
        eclipse$setNestedVisible(model.rightArm, "eclipse_upper_arm", "eclipse_upper_sleeve", model.rightSleeve.visible);
        eclipse$setNestedVisible(model.rightArm, "eclipse_forearm", "eclipse_forearm_sleeve", model.rightSleeve.visible);
        eclipse$setNestedVisible(model.leftArm, "eclipse_upper_arm", "eclipse_upper_sleeve", model.leftSleeve.visible);
        eclipse$setNestedVisible(model.leftArm, "eclipse_forearm", "eclipse_forearm_sleeve", model.leftSleeve.visible);
        eclipse$setNestedVisible(model.rightLeg, "eclipse_thigh", "eclipse_thigh_pants", model.rightPants.visible);
        eclipse$setNestedVisible(model.rightLeg, "eclipse_shin", "eclipse_shin_pants", model.rightPants.visible);
        eclipse$setNestedVisible(model.leftLeg, "eclipse_thigh", "eclipse_thigh_pants", model.leftPants.visible);
        eclipse$setNestedVisible(model.leftLeg, "eclipse_shin", "eclipse_shin_pants", model.leftPants.visible);
    }

    @Unique
    private void eclipse$anchorUpperBodyAtHips(PlayerModel model) {
        float pitch = eclipse$clamp(model.body.xRot, -0.78f, 0.78f);
        float hipDistance = 12.0f;
        float yCompensation = hipDistance * (1.0f - (float) Math.cos(pitch));
        float zCompensation = -hipDistance * (float) Math.sin(pitch);

        model.body.y += yCompensation;
        model.body.z += zCompensation;
        model.head.y += yCompensation;
        model.head.z += zCompensation;
        model.leftArm.y += yCompensation;
        model.leftArm.z += zCompensation;
        model.rightArm.y += yCompensation;
        model.rightArm.z += zCompensation;
    }

    @Unique
    private void eclipse$resetWearableLocalPose(ModelPart part) {
        if (part != null) {
            part.loadPose(part.getInitialPose());
        }
    }

    @Unique
    private void eclipse$setNestedVisible(ModelPart root, String boneName, String layerName, boolean visible) {
        ModelPart bone = eclipse$getChildOrNull(root, boneName);
        ModelPart layer = eclipse$getChildOrNull(bone, layerName);
        if (layer != null) {
            layer.visible = visible;
        }
    }

    @Unique
    private void eclipse$setLowerArm(ModelPart arm, ModelPart sleeve, float bend) {
        ModelPart forearm = eclipse$getChildOrNull(arm, "eclipse_forearm");
        if (forearm != null) {
            forearm.xRot = -bend;
        }
    }

    @Unique
    private void eclipse$blendHammerLowerArm(ModelPart arm, float bend, float wristTwist, float weight) {
        ModelPart forearm = eclipse$getChildOrNull(arm, "eclipse_forearm");
        if (forearm != null) {
            forearm.xRot = eclipse$lerp(forearm.xRot, -bend, weight);
            // У текущего рига нет отдельной кости кисти. Y-поворот всего предплечья после X-сгиба
            // смещает конец руки и ломает IK-хват; twist допустим только для будущей отдельной кисти.
            forearm.yRot = eclipse$lerp(forearm.yRot,
                    ArticulatedLimbLayout.forearmYForTwoHandedGrip(wristTwist), weight);
            forearm.zRot = eclipse$lerp(forearm.zRot, 0.0f, weight);
        }
    }

    @Unique
    private void eclipse$setLowerLeg(ModelPart leg, ModelPart pants, float bend) {
        ModelPart shin = eclipse$getChildOrNull(leg, "eclipse_shin");
        if (shin != null) {
            shin.xRot = bend;
        }
    }

    @Unique
    private void eclipse$blendLowerLeg(ModelPart leg, float bend, float weight) {
        ModelPart shin = eclipse$getChildOrNull(leg, "eclipse_shin");
        if (shin != null) {
            shin.xRot = eclipse$lerp(shin.xRot, bend, weight);
        }
    }

    @Unique
    private float eclipse$lerp(float from, float to, float amount) {
        return from + (to - from) * eclipse$clamp(amount, 0.0f, 1.0f);
    }

    @Unique
    private ModelPart eclipse$getChildOrNull(ModelPart parent, String child) {
        try {
            return parent == null ? null : parent.getChild(child);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Unique
    private void eclipse$resetModelTranslations(PlayerModel model, AvatarRenderState state) {
        model.body.y = state.isCrouching ? 3.2f : 0.0f;
        model.body.z = 0.0f;
        
        model.head.y = state.isCrouching ? 4.2f : 0.0f;
        model.head.z = 0.0f;
        
        model.leftArm.x = BreathingShoulderLayout.BASE_SHOULDER_X;
        model.leftArm.y = state.isCrouching ? 5.2f : BreathingShoulderLayout.BASE_SHOULDER_Y;
        model.leftArm.z = 0.0f;
        
        model.rightArm.x = -BreathingShoulderLayout.BASE_SHOULDER_X;
        model.rightArm.y = state.isCrouching ? 5.2f : BreathingShoulderLayout.BASE_SHOULDER_Y;
        model.rightArm.z = 0.0f;
        
        model.leftLeg.x = ArticulatedLimbLayout.LEG_HIP_X;
        model.leftLeg.y = state.isCrouching ? 12.2f : 12.0f;
        model.leftLeg.z = state.isCrouching ? 4.0f : 0.0f;
        
        model.rightLeg.x = -ArticulatedLimbLayout.LEG_HIP_X;
        model.rightLeg.y = state.isCrouching ? 12.2f : 12.0f;
        model.rightLeg.z = state.isCrouching ? 4.0f : 0.0f;
    }

    @Unique
    private float eclipse$wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) {
            value -= 360.0f;
        }
        if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }
}
