package ua.rp.chat.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(LivingEntityRenderer.class)
public class PlayerRendererMixin {
    @Unique private boolean oasis$snapshotActive;
    @Unique private boolean oasis$headVisible;
    @Unique private boolean oasis$hatVisible;
    @Unique private boolean oasis$headSkipDraw;
    @Unique private boolean oasis$hatSkipDraw;
    @Unique private boolean oasis$bodyVisible;
    @Unique private boolean oasis$jacketVisible;
    @Unique private boolean oasis$firstPersonSnapshot;

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("RETURN"))
    private void oasis$onExtract(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state instanceof LocalPlayerRenderState lprs) {
            lprs.oasis$setLocalPlayer(entity == Minecraft.getInstance().player);
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void oasis$beforeSubmit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        SmartCameraManager cameraManager = SmartCameraManager.getInstance();
        if (oasis$isLocalFirstPersonState(state) && cameraManager.isWorldFirstPersonBodyRender()) {
            LivingEntityRenderer<?, ?, ?> renderer = (LivingEntityRenderer<?, ?, ?>) (Object) this;
            if (renderer.getModel() instanceof PlayerModel model) {
                oasis$captureVisibility(model, true);
                cameraManager.setSubmittingFirstPersonPlayer(true);
                cameraManager.applyFirstPersonBodyPose(model);
            }
        } else if (oasis$isLocalFirstPersonState(state)) {
            LivingEntityRenderer<?, ?, ?> renderer = (LivingEntityRenderer<?, ?, ?>) (Object) this;
            if (renderer.getModel() instanceof PlayerModel model) {
                oasis$restoreHeadForNormalLocalRender(model, state);
            }
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
    private void oasis$afterSubmit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (oasis$isLocalFirstPersonState(state)) {
            SmartCameraManager cameraManager = SmartCameraManager.getInstance();
            LivingEntityRenderer<?, ?, ?> renderer = (LivingEntityRenderer<?, ?, ?>) (Object) this;
            if (renderer.getModel() instanceof PlayerModel model) {
                oasis$restoreVisibility(model, state);
            }
            cameraManager.setSubmittingFirstPersonPlayer(false);
            if (cameraManager.isRenderingFirstPersonPlayer()) {
                cameraManager.setRenderingFirstPersonPlayer(false);
            }
        }
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
    private void oasis$captureVisibility(PlayerModel model, boolean firstPerson) {
        oasis$snapshotActive = true;
        oasis$firstPersonSnapshot = firstPerson;
        oasis$headVisible = model.head.visible;
        oasis$hatVisible = model.hat.visible;
        oasis$headSkipDraw = model.head.skipDraw;
        oasis$hatSkipDraw = model.hat.skipDraw;
        oasis$bodyVisible = model.body.visible;
        oasis$jacketVisible = model.jacket.visible;
    }

    @Unique
    private void oasis$restoreVisibility(PlayerModel model, EntityRenderState state) {
        if (!oasis$snapshotActive) {
            return;
        }

        if (oasis$firstPersonSnapshot) {
            model.body.visible = oasis$bodyVisible;
            model.jacket.visible = oasis$jacketVisible;
            oasis$snapshotActive = false;
            oasis$firstPersonSnapshot = false;
            return;
        }

        if (state instanceof AvatarRenderState avatar) {
            model.head.visible = true;
            model.hat.visible = avatar.showHat;
            model.head.skipDraw = false;
            model.hat.skipDraw = false;
            model.body.visible = true;
            model.jacket.visible = avatar.showJacket;
            oasis$snapshotActive = false;
            return;
        }

        model.head.visible = oasis$headVisible;
        model.hat.visible = oasis$hatVisible;
        model.head.skipDraw = oasis$headSkipDraw;
        model.hat.skipDraw = oasis$hatSkipDraw;
        model.body.visible = oasis$bodyVisible;
        model.jacket.visible = oasis$jacketVisible;
        oasis$snapshotActive = false;
    }

    @Unique
    private void oasis$restoreHeadForNormalLocalRender(PlayerModel model, EntityRenderState state) {
        model.head.visible = true;
        model.head.skipDraw = false;
        model.hat.skipDraw = false;
        model.hat.visible = !(state instanceof AvatarRenderState avatar) || avatar.showHat;
    }
}
