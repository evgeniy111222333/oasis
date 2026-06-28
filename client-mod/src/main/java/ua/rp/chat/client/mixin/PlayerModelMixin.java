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
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.debug.OasisPoseDebugExporter;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    @Inject(method = "createMesh(Lnet/minecraft/client/model/geom/builders/CubeDeformation;Z)Lnet/minecraft/client/model/geom/builders/MeshDefinition;", at = @At("RETURN"))
    private static void oasis$createSegmentedMesh(CubeDeformation deformation, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
        // Custom segmented limbs are disabled until the model layer pipeline is rebuilt safely.
    }

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("RETURN"))
    private void oasis$afterSetupAnim(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        oasis$applyStableRoleplayPose(model, state);
        if (oasis$isLocalFirstPersonState(state) && SmartCameraManager.getInstance().isFirstPersonBodyEnabled()) {
            SmartCameraManager.getInstance().applyFirstPersonBodyPose(model);
        }
        Player player = oasis$getRenderedPlayer(state);
        OasisPoseDebugExporter.capture(model, state, player, oasis$isLocalFirstPersonState(state));
    }

    @Unique
    private void oasis$applyStableRoleplayPose(PlayerModel model, AvatarRenderState state) {
        if (state == null || model == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        float moving = oasis$clamp(state.walkAnimationSpeed * 3.2f, 0.0f, 1.0f);
        // НЕ повне згасання — реальні люди дихають i при ходьбі.
        // minCalm 0.30 гарантує видиму амплітуду дихання під час руху.
        float calmBase = state.isCrouching ? 0.55f : 1.0f;
        float calm = calmBase * Math.max(0.30f, 1.0f - moving * 0.6f);

        // --- ДИХАННЯ ---
        // ~13 дихань/хв при 20 TPS. Амплітуди підібрані так, щоб рух було
        // видно неозброєним оком (кути ~2-3° замість колишніх 0.3-0.5°).
        // ВАЖЛИВО: НЕ модифікуємо body.y — це рвало тулуб від голови.
        float breath = (float) Math.sin(state.ageInTicks * 0.06981317f);
        float breathLift = breath * calm;
        model.body.xRot += breathLift * 0.045f;
        model.leftArm.xRot += breathLift * 0.055f;
        model.rightArm.xRot += breathLift * 0.055f;
        model.leftArm.zRot += breathLift * 0.025f;
        model.rightArm.zRot -= breathLift * 0.025f;

        // Легкий ваго-перенос (вес тела переносится с ноги на ногу).
        float idleShift = (float) Math.sin(state.ageInTicks * 0.01396263f) * calm;
        model.body.zRot += idleShift * 0.010f;
        model.leftArm.zRot += idleShift * 0.004f;
        model.rightArm.zRot += idleShift * 0.004f;

        // --- НАХИЛ ПРИ ПОГЛЯДІ ВНИЗ ---
        // Поріг знижено з 58°→86° до 25°→65°: нахил починається раніше,
        // відчувається природніше (дивлюся на землю — тулуб нахиляється).
        // ВАЖЛИВО: НЕ зсуваємо body.y/body.z — це рвало модель.
        // Тільки оберт body.xRot (плечі й шия залишаються нерухомими
        // при оберті навколо X — точка кріплення голови (0,0,0) не змінюється).
        float lookDown = oasis$smoothStep(25.0f, 65.0f, oasis$clamp(state.xRot, 0.0f, 90.0f));
        float lean = lookDown * (state.isCrouching ? 0.5f : 1.0f);
        float torsoLeanDelta = lean * 0.22f;
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
        float lookSide = oasis$clamp(oasis$wrapDegrees(state.yRot - state.bodyRot) / 90.0f, -1.0f, 1.0f);
        float upperTurn = lookSide * (0.025f + calm * 0.025f);
        model.body.yRot += upperTurn;
        model.leftArm.yRot += upperTurn * 0.45f;
        model.rightArm.yRot += upperTurn * 0.45f;

        oasis$applyStableArmStance(model, state, moving, calm);
        oasis$syncWearableLayers(model);
    }

    @Unique
    private void oasis$applyStableArmStance(PlayerModel model, AvatarRenderState state, float moving, float calm) {
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
    private void oasis$applySharedRoleplayPose(PlayerModel model, AvatarRenderState state) {
        if (state == null || model == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return;
        }

        float moving = oasis$clamp(state.walkAnimationSpeed * 3.8f, 0.0f, 1.0f);
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

        float lookDown = oasis$smoothStep(65.0f, 88.0f, oasis$clamp(state.xRot, 0.0f, 90.0f));
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

        float lookSide = oasis$clamp(oasis$wrapDegrees(state.yRot - state.bodyRot) / 90.0f, -1.0f, 1.0f);
        float upperTurn = lookSide * (0.035f + calm * 0.035f);
        model.body.yRot += upperTurn;
        model.leftArm.yRot += upperTurn * 0.6f;
        model.rightArm.yRot += upperTurn * 0.6f;

        Player player = oasis$getRenderedPlayer(state);
        float terrain = oasis$getTerrainBalance(player);
        if (Math.abs(terrain) > 0.01f && moving < 0.45f) {
            model.body.zRot += terrain * 0.045f;
            model.leftLeg.xRot -= Math.max(0.0f, terrain) * 0.10f;
            model.rightLeg.xRot += Math.min(0.0f, terrain) * 0.10f;
            model.leftLeg.zRot += terrain * 0.025f;
            model.rightLeg.zRot += terrain * 0.025f;
        }

        float runLean = moving * oasis$clamp(state.speedValue * 0.45f, 0.0f, 1.0f);
        model.body.xRot += runLean * 0.055f;
        model.leftArm.xRot -= runLean * 0.035f;
        model.rightArm.xRot -= runLean * 0.035f;

        oasis$applyWeaponStance(model, state, moving);
        oasis$applyWeatherPosture(model, player, calm);
        oasis$applyArticulatedLimbs(model, state, moving, calm, lean);
    }

    @Unique
    private static void oasis$replaceArm(PartDefinition root, String armName, String sleeveName, float x, boolean right, boolean slim, int texX, int texY, int sleeveTexX, int sleeveTexY, CubeDeformation deformation) {
        int width = slim ? 3 : 4;
        float minX = right ? (slim ? -2.0f : -3.0f) : -1.0f;
        PartDefinition arm = root.addOrReplaceChild(armName, CubeListBuilder.create(), PartPose.offset(x, 2.0f, 0.0f));
        arm.addOrReplaceChild("oasis_upper_arm", CubeListBuilder.create().texOffs(texX, texY).addBox(minX, -2.0f, -2.0f, width, 5.8f, 4, deformation), PartPose.ZERO);
        arm.addOrReplaceChild("oasis_forearm", CubeListBuilder.create().texOffs(texX, texY + 6).addBox(minX, 0.0f, -2.0f, width, 6.2f, 4, deformation), PartPose.offset(0.0f, 3.8f, 0.0f));

        CubeDeformation sleeve = deformation.extend(0.25f);
        PartDefinition sleeveRoot = root.addOrReplaceChild(sleeveName, CubeListBuilder.create(), PartPose.offset(x, 2.0f, 0.0f));
        sleeveRoot.addOrReplaceChild("oasis_upper_sleeve", CubeListBuilder.create().texOffs(sleeveTexX, sleeveTexY).addBox(minX, -2.0f, -2.0f, width, 5.8f, 4, sleeve), PartPose.ZERO);
        sleeveRoot.addOrReplaceChild("oasis_forearm_sleeve", CubeListBuilder.create().texOffs(sleeveTexX, sleeveTexY + 6).addBox(minX, 0.0f, -2.0f, width, 6.2f, 4, sleeve), PartPose.offset(0.0f, 3.8f, 0.0f));
    }

    @Unique
    private static void oasis$replaceLeg(PartDefinition root, String legName, String pantsName, float x, int texX, int texY, int pantsTexX, int pantsTexY, CubeDeformation deformation) {
        PartDefinition leg = root.addOrReplaceChild(legName, CubeListBuilder.create(), PartPose.offset(x, 12.0f, 0.0f));
        leg.addOrReplaceChild("oasis_thigh", CubeListBuilder.create().texOffs(texX, texY).addBox(-2.0f, 0.0f, -2.0f, 4, 5.9f, 4, deformation), PartPose.ZERO);
        PartDefinition shin = leg.addOrReplaceChild("oasis_shin", CubeListBuilder.create().texOffs(texX, texY + 6).addBox(-2.0f, 0.0f, -2.0f, 4, 5.7f, 4, deformation), PartPose.offset(0.0f, 5.9f, 0.0f));
        shin.addOrReplaceChild("oasis_foot", CubeListBuilder.create().texOffs(texX, texY + 10).addBox(-2.0f, 4.2f, -2.6f, 4, 1.8f, 4.8f, deformation), PartPose.ZERO);

        CubeDeformation pants = deformation.extend(0.25f);
        PartDefinition pantsRoot = root.addOrReplaceChild(pantsName, CubeListBuilder.create(), PartPose.offset(x, 12.0f, 0.0f));
        pantsRoot.addOrReplaceChild("oasis_thigh_pants", CubeListBuilder.create().texOffs(pantsTexX, pantsTexY).addBox(-2.0f, 0.0f, -2.0f, 4, 5.9f, 4, pants), PartPose.ZERO);
        PartDefinition pantsShin = pantsRoot.addOrReplaceChild("oasis_shin_pants", CubeListBuilder.create().texOffs(pantsTexX, pantsTexY + 6).addBox(-2.0f, 0.0f, -2.0f, 4, 5.7f, 4, pants), PartPose.offset(0.0f, 5.9f, 0.0f));
        pantsShin.addOrReplaceChild("oasis_foot_pants", CubeListBuilder.create().texOffs(pantsTexX, pantsTexY + 10).addBox(-2.0f, 4.2f, -2.6f, 4, 1.8f, 4.8f, pants), PartPose.ZERO);
    }

    @Unique
    private boolean oasis$isLocalFirstPersonState(EntityRenderState state) {
        if (state instanceof LocalPlayerRenderState lprs && lprs.oasis$isLocalPlayer()) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        return state instanceof AvatarRenderState avatar
                && client != null
                && client.player != null
                && avatar.id == client.player.getId();
    }

    @Unique
    private float oasis$smoothStep(float edge0, float edge1, float value) {
        float x = oasis$clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    @Unique
    private float oasis$clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Unique
    private Player oasis$getRenderedPlayer(AvatarRenderState state) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || state == null) {
            return null;
        }
        return client.level.getEntity(state.id) instanceof Player player ? player : null;
    }

    @Unique
    private float oasis$getTerrainBalance(Player player) {
        if (player == null || player.level() == null || !player.onGround()) {
            return 0.0f;
        }
        double yaw = Math.toRadians(player.getYRot());
        double sideX = Math.cos(yaw) * 0.24;
        double sideZ = Math.sin(yaw) * 0.24;
        double left = oasis$getSupportHeight(player, sideX, sideZ);
        double right = oasis$getSupportHeight(player, -sideX, -sideZ);
        return oasis$clamp((float) (left - right), -0.55f, 0.55f);
    }

    @Unique
    private double oasis$getSupportHeight(Player player, double offsetX, double offsetZ) {
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
    private void oasis$applyWeaponStance(PlayerModel model, AvatarRenderState state, float moving) {
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
    private void oasis$applyWeatherPosture(PlayerModel model, Player player, float calm) {
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
    private void oasis$applyArticulatedLimbs(PlayerModel model, AvatarRenderState state, float moving, float calm, float lookDownLean) {
        float step = (float) Math.sin(state.walkAnimationPos * 0.6662f);
        float oppositeStep = (float) Math.sin(state.walkAnimationPos * 0.6662f + Math.PI);
        float walk = oasis$clamp(moving, 0.0f, 1.0f);
        float idleArmBend = 0.18f * calm;
        float lookBend = 0.24f * lookDownLean;
        float runTuck = walk * (state.speedValue > 0.12f ? 0.08f : 0.0f);

        oasis$setLowerArm(model.rightArm, model.rightSleeve, idleArmBend + lookBend + runTuck + walk * (0.13f + Math.max(0.0f, -step) * 0.22f));
        oasis$setLowerArm(model.leftArm, model.leftSleeve, idleArmBend + lookBend + runTuck + walk * (0.13f + Math.max(0.0f, -oppositeStep) * 0.22f));

        float rightKnee = 0.06f * calm + walk * (0.10f + Math.max(0.0f, step) * 0.38f);
        float leftKnee = 0.06f * calm + walk * (0.10f + Math.max(0.0f, oppositeStep) * 0.38f);
        if (state.isCrouching) {
            rightKnee += 0.22f;
            leftKnee += 0.22f;
        }

        oasis$setLowerLeg(model.rightLeg, model.rightPants, rightKnee);
        oasis$setLowerLeg(model.leftLeg, model.leftPants, leftKnee);

        float stance = 0.16f + calm * 0.07f + walk * 0.04f;
        model.rightLeg.x -= stance;
        model.leftLeg.x += stance;
        model.rightLeg.zRot -= 0.018f * calm;
        model.leftLeg.zRot += 0.018f * calm;

        oasis$syncWearableLayers(model);
    }

    @Unique
    private void oasis$syncWearableLayers(PlayerModel model) {
        oasis$copyPose(model.rightArm, model.rightSleeve);
        oasis$copyPose(model.leftArm, model.leftSleeve);
        oasis$copyPose(model.rightLeg, model.rightPants);
        oasis$copyPose(model.leftLeg, model.leftPants);
        oasis$copyPose(model.body, model.jacket);
    }

    @Unique
    private void oasis$copyPose(ModelPart from, ModelPart to) {
        if (from == null || to == null) {
            return;
        }
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
    }

    @Unique
    private void oasis$setLowerArm(ModelPart arm, ModelPart sleeve, float bend) {
        ModelPart forearm = oasis$getChildOrNull(arm, "oasis_forearm");
        if (forearm != null) {
            forearm.xRot = bend;
        }
        ModelPart sleeveForearm = oasis$getChildOrNull(sleeve, "oasis_forearm_sleeve");
        if (sleeveForearm != null) {
            sleeveForearm.xRot = bend;
        }
    }

    @Unique
    private void oasis$setLowerLeg(ModelPart leg, ModelPart pants, float bend) {
        ModelPart shin = oasis$getChildOrNull(leg, "oasis_shin");
        if (shin != null) {
            shin.xRot = -bend;
        }
        ModelPart pantsShin = oasis$getChildOrNull(pants, "oasis_shin_pants");
        if (pantsShin != null) {
            pantsShin.xRot = -bend;
        }
    }

    @Unique
    private ModelPart oasis$getChildOrNull(ModelPart parent, String child) {
        try {
            return parent == null ? null : parent.getChild(child);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Unique
    private float oasis$wrapDegrees(float value) {
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
