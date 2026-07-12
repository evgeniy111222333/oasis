package ua.rp.chat.client.mixin;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.AcquaintanceClientState;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.debug.EclipsePoseDebugExporter;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    @Inject(method = "createMesh(Lnet/minecraft/client/model/geom/builders/CubeDeformation;Z)Lnet/minecraft/client/model/geom/builders/MeshDefinition;", at = @At("RETURN"))
    private static void eclipse$createSegmentedMesh(CubeDeformation deformation, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
        MeshDefinition mesh = cir.getReturnValue();
        if (mesh == null) {
            return;
        }
        PartDefinition root = mesh.getRoot();
        eclipse$replaceArm(root, "right_arm", "right_sleeve", -5.0f, true, slim, 40, 16, 40, 32, deformation);
        eclipse$replaceArm(root, "left_arm", "left_sleeve", 5.0f, false, slim, 32, 48, 48, 48, deformation);
        eclipse$replaceLeg(root, "right_leg", "right_pants", -1.9f, 0, 16, 0, 32, deformation);
        eclipse$replaceLeg(root, "left_leg", "left_pants", 1.9f, 16, 48, 0, 48, deformation);
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
        eclipse$applyPhysicalInteractionPose(model, state, player);
        if (localFirstPerson) {
            eclipse$anchorUpperBodyAtHips(model);
            eclipse$syncWearableLayers(model);
        }
        EclipsePoseDebugExporter.capture(model, state, player, eclipse$isLocalFirstPersonState(state));
    }

    @Unique
    private void eclipse$applyStableRoleplayPose(PlayerModel model, AvatarRenderState state) {
        if (state == null || model == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        float moving = eclipse$clamp(state.walkAnimationSpeed * 3.2f, 0.0f, 1.0f);
        ItemStack main = state.getMainHandItemStack();
        String heldItem = main == null || main.isEmpty() ? "" : main.getItem().toString().toLowerCase();
        boolean heavyItem = heldItem.contains("axe") || heldItem.contains("mace") || heldItem.contains("hammer") || heldItem.contains("great") || heldItem.contains("halberd");
        boolean aiming = state.isUsingItem && (heldItem.contains("bow") || heldItem.contains("crossbow") || heldItem.contains("trident") || heldItem.contains("spear"));
        // НЕ повне згасання — реальні люди дихають i при ходьбі.
        // minCalm 0.30 гарантує видиму амплітуду дихання під час руху.
        float calmBase = state.isCrouching ? 0.62f : 1.0f;
        float stamina = ua.rp.chat.client.vitals.VitalsClientState.getStamina01();
        float fatigue = 1.0f - stamina;
        float motionDamp = 1.0f - moving * 0.55f;
        if (heavyItem) {
            motionDamp *= 0.86f;
        }
        if (aiming) {
            motionDamp *= 0.55f;
        }
        float calm = calmBase * eclipse$clamp(motionDamp, 0.28f, 1.0f);

        // --- ДИХАННЯ ---
        // ~13 дихань/хв при 20 TPS. Амплітуди підібрані так, щоб рух було
        // видно неозброєним оком (кути ~2-3° замість колишніх 0.3-0.5°).
        // ВАЖЛИВО: НЕ модифікуємо body.y — це рвало тулуб від голови.
        float breathRate = 0.0114f + moving * 0.0038f + eclipse$clamp(state.speedValue, 0.0f, 0.35f) * 0.0030f + fatigue * 0.017f;
        float breath = eclipse$breathCurve(state.ageInTicks * breathRate);
        float inhale = breath * calm;
        float exhale = (1.0f - breath) * calm;
        float micro = ((float) Math.sin(state.ageInTicks * 0.173f + 0.7f) * 0.10f
                + (float) Math.sin(state.ageInTicks * 0.041f + 1.9f) * 0.07f) * calm;
        float breathLift = (inhale - exhale * 0.18f + micro * 0.12f) * (1.0f + fatigue * 1.15f);
        model.body.xRot += breathLift * (0.010f + fatigue * 0.010f);
        model.leftArm.xRot += breathLift * (0.022f + fatigue * 0.020f);
        model.rightArm.xRot += breathLift * (0.022f + fatigue * 0.020f);
        model.leftArm.zRot += breathLift * (0.012f + fatigue * 0.012f);
        model.rightArm.zRot -= breathLift * (0.012f + fatigue * 0.012f);
        model.leftArm.yRot -= inhale * (0.006f + fatigue * 0.009f);
        model.rightArm.yRot += inhale * (0.006f + fatigue * 0.009f);
        if (fatigue > 0.55f && moving < 0.25f) {
            float exhausted = (fatigue - 0.55f) / 0.45f;
            model.body.xRot += exhausted * 0.035f;
            model.leftArm.xRot += exhausted * 0.080f;
            model.rightArm.xRot += exhausted * 0.080f;
        }

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
    private void eclipse$applySharedRoleplayPose(PlayerModel model, AvatarRenderState state) {
        if (state == null || model == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        float moving = eclipse$clamp(state.walkAnimationSpeed * 3.8f, 0.0f, 1.0f);
        float calm = 1.0f - moving;
        if (state.isCrouching) {
            calm *= 0.65f;
        }

        float breath = (float) Math.sin(state.ageInTicks * 0.07853982f); // 80 ticks per calm breath, about 15/min.
        float breathLift = breath * calm;

        model.body.xRot += breathLift * 0.010f;
        model.body.y -= breathLift * 0.045f;
        model.leftArm.xRot += breathLift * 0.012f;
        model.rightArm.xRot += breathLift * 0.012f;
        model.leftArm.zRot += breathLift * 0.006f;
        model.rightArm.zRot -= breathLift * 0.006f;

        float idleShift = (float) Math.sin(state.ageInTicks * 0.01745329f) * calm; // About one weight shift every 18s.
        model.body.zRot += idleShift * 0.018f;
        model.leftLeg.zRot += idleShift * 0.010f;
        model.rightLeg.zRot += idleShift * 0.010f;

        float lookDown = eclipse$smoothStep(65.0f, 88.0f, eclipse$clamp(state.xRot, 0.0f, 90.0f));
        float crouchGuard = state.isCrouching ? 0.55f : 1.0f;
        float lean = lookDown * crouchGuard;
        model.body.xRot += lean * 0.26f;
        model.body.y += lean * 0.34f;
        model.body.z -= lean * 0.36f;

        model.leftArm.xRot -= lean * 0.24f;
        model.rightArm.xRot -= lean * 0.24f;
        model.leftArm.yRot += lean * 0.06f;
        model.rightArm.yRot -= lean * 0.06f;
        model.leftArm.z -= lean * 0.28f;
        model.rightArm.z -= lean * 0.28f;

        model.leftLeg.xRot -= lean * 0.10f;
        model.rightLeg.xRot -= lean * 0.10f;
        model.leftLeg.zRot += lean * 0.025f;
        model.rightLeg.zRot -= lean * 0.025f;

        float lookSide = eclipse$clamp(eclipse$wrapDegrees(state.yRot - state.bodyRot) / 90.0f, -1.0f, 1.0f);
        float upperTurn = lookSide * (0.035f + calm * 0.035f);
        model.body.yRot += upperTurn;
        model.leftArm.yRot += upperTurn * 0.6f;
        model.rightArm.yRot += upperTurn * 0.6f;

        Player player = eclipse$getRenderedPlayer(state);
        float terrain = eclipse$getTerrainBalance(player);
        if (Math.abs(terrain) > 0.01f && moving < 0.45f) {
            model.body.zRot += terrain * 0.045f;
            model.leftLeg.xRot -= Math.max(0.0f, terrain) * 0.10f;
            model.rightLeg.xRot += Math.min(0.0f, terrain) * 0.10f;
            model.leftLeg.zRot += terrain * 0.025f;
            model.rightLeg.zRot += terrain * 0.025f;
        }

        float runLean = moving * eclipse$clamp(state.speedValue * 0.45f, 0.0f, 1.0f);
        model.body.xRot += runLean * 0.055f;
        model.leftArm.xRot -= runLean * 0.035f;
        model.rightArm.xRot -= runLean * 0.035f;

        eclipse$applyWeaponStance(model, state, moving);
        eclipse$applyWeatherPosture(model, player, calm);
        eclipse$applyArticulatedLimbs(model, state, moving, calm, lean);
    }

    @Unique
    private static void eclipse$replaceArm(PartDefinition root, String armName, String sleeveName, float x, boolean right, boolean slim, int texX, int texY, int sleeveTexX, int sleeveTexY, CubeDeformation deformation) {
        int width = slim ? 3 : 4;
        float minX = right ? (slim ? -2.0f : -3.0f) : -1.0f;
        PartDefinition arm = root.addOrReplaceChild(armName, CubeListBuilder.create(), PartPose.offset(x, 2.0f, 0.0f));
        
        // Upper arm with joint filler block at the bottom-back (slightly smaller to prevent Z-fighting)
        PartDefinition upperArm = arm.addOrReplaceChild("eclipse_upper_arm", 
                CubeListBuilder.create()
                        .texOffs(texX, texY).addBox(minX, -2.0f, -2.0f, width, 5.8f, 4, deformation)
                        .texOffs(texX, texY + 4).addBox(minX + 0.01f, 3.8f, 0.0f, width - 0.02f, 1.0f, 1.98f, deformation), 
                PartPose.ZERO);
        
        // Forearm with pivot shifted down and backward (Y=4.0, Z=1.2) to prevent the "tooth"
        PartDefinition forearm = arm.addOrReplaceChild("eclipse_forearm", 
                CubeListBuilder.create().texOffs(texX, texY + 6).addBox(minX, -0.2f, -3.2f, width, 6.4f, 4, deformation), 
                PartPose.offset(0.0f, 4.0f, 1.2f));

        CubeDeformation sleeve = deformation.extend(0.25f);
        
        // Upper sleeve with joint filler
        upperArm.addOrReplaceChild("eclipse_upper_sleeve", 
                CubeListBuilder.create()
                        .texOffs(sleeveTexX, sleeveTexY).addBox(minX, -2.0f, -2.0f, width, 5.8f, 4, sleeve)
                        .texOffs(sleeveTexX, sleeveTexY + 4).addBox(minX + 0.01f, 3.8f, 0.0f, width - 0.02f, 1.0f, 1.98f, sleeve), 
                PartPose.ZERO);
        
        // Forearm sleeve with shifted pivot
        forearm.addOrReplaceChild("eclipse_forearm_sleeve", 
                CubeListBuilder.create().texOffs(sleeveTexX, sleeveTexY + 6).addBox(minX, -0.2f, -3.2f, width, 6.4f, 4, sleeve), 
                PartPose.ZERO);
        
        arm.addOrReplaceChild(sleeveName, CubeListBuilder.create(), PartPose.ZERO);
    }

    @Unique
    private static void eclipse$replaceLeg(PartDefinition root, String legName, String pantsName, float x, int texX, int texY, int pantsTexX, int pantsTexY, CubeDeformation deformation) {
        PartDefinition leg = root.addOrReplaceChild(legName, CubeListBuilder.create(), PartPose.offset(x, 12.0f, 0.0f));
        
        // Thigh with joint filler at the bottom-front (slightly smaller to prevent Z-fighting)
        PartDefinition thigh = leg.addOrReplaceChild("eclipse_thigh", 
                CubeListBuilder.create()
                        .texOffs(texX, texY).addBox(-2.0f, 0.0f, -2.0f, 4, 6.0f, 4, deformation)
                        .texOffs(texX, texY + 4).addBox(-1.99f, 6.0f, -1.99f, 3.98f, 1.0f, 1.98f, deformation), 
                PartPose.ZERO);
        
        // Shin with pivot shifted down and forward (Y=6.2, Z=-1.2) to prevent the "tooth"
        PartDefinition shin = leg.addOrReplaceChild("eclipse_shin", 
                CubeListBuilder.create().texOffs(texX, texY + 6).addBox(-2.0f, -0.2f, -0.8f, 4, 6.2f, 4, deformation), 
                PartPose.offset(0.0f, 6.2f, -1.2f));

        CubeDeformation pants = deformation.extend(0.25f);
        
        // Thigh pants with joint filler
        thigh.addOrReplaceChild("eclipse_thigh_pants", 
                CubeListBuilder.create()
                        .texOffs(pantsTexX, pantsTexY).addBox(-2.0f, 0.0f, -2.0f, 4, 6.0f, 4, pants)
                        .texOffs(pantsTexX, pantsTexY + 4).addBox(-1.99f, 6.0f, -1.99f, 3.98f, 1.0f, 1.98f, pants), 
                PartPose.ZERO);
        
        // Shin pants with shifted pivot
        shin.addOrReplaceChild("eclipse_shin_pants", 
                CubeListBuilder.create().texOffs(pantsTexX, pantsTexY + 6).addBox(-2.0f, 0.0f, -0.8f, 4, 6.0f, 4, pants), 
                PartPose.ZERO);
        
        leg.addOrReplaceChild(pantsName, CubeListBuilder.create(), PartPose.ZERO);
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
    private float eclipse$breathCurve(float cycles) {
        float phase = cycles - (float) Math.floor(cycles);
        if (phase < 0.34f) {
            return eclipse$smoothStep(0.0f, 0.34f, phase);
        }
        if (phase < 0.88f) {
            return 1.0f - eclipse$smoothStep(0.34f, 0.88f, phase);
        }
        return 0.0f;
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
    private void eclipse$setLowerLeg(ModelPart leg, ModelPart pants, float bend) {
        ModelPart shin = eclipse$getChildOrNull(leg, "eclipse_shin");
        if (shin != null) {
            shin.xRot = bend;
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
        
        model.leftArm.y = state.isCrouching ? 5.2f : 2.0f;
        model.leftArm.z = 0.0f;
        
        model.rightArm.y = state.isCrouching ? 5.2f : 2.0f;
        model.rightArm.z = 0.0f;
        
        model.leftLeg.y = state.isCrouching ? 12.2f : 12.0f;
        model.leftLeg.z = state.isCrouching ? 4.0f : 0.0f;
        
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
