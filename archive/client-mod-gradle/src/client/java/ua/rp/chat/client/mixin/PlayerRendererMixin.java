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
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.model.geom.ModelPart;
import ua.rp.chat.client.mixin.ModelPartAccessor;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(LivingEntityRenderer.class)
public class PlayerRendererMixin {
    @Unique private static boolean eclipse$debugPrinted = false;
    @Unique private boolean eclipse$snapshotActive;
    @Unique private boolean eclipse$headVisible;
    @Unique private boolean eclipse$hatVisible;
    @Unique private boolean eclipse$headSkipDraw;
    @Unique private boolean eclipse$hatSkipDraw;
    @Unique private boolean eclipse$bodyVisible;
    @Unique private boolean eclipse$jacketVisible;
    @Unique private boolean eclipse$firstPersonSnapshot;
    @Unique private boolean eclipse$bodyCompensationPushed;

    @Unique private float eclipse$bodyY;
    @Unique private float eclipse$bodyZ;
    @Unique private float eclipse$headY;
    @Unique private float eclipse$headZ;
    @Unique private float eclipse$leftArmY;
    @Unique private float eclipse$leftArmZ;
    @Unique private float eclipse$rightArmY;
    @Unique private float eclipse$rightArmZ;

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("RETURN"))
    private void eclipse$onExtract(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state instanceof LocalPlayerRenderState lprs) {
            lprs.eclipse$setLocalPlayer(entity == Minecraft.getInstance().player);
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void eclipse$beforeSubmit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        SmartCameraManager cameraManager = SmartCameraManager.getInstance();
        if (eclipse$isLocalFirstPersonState(state) && cameraManager.isWorldFirstPersonBodyRender()) {
            Vec3 compensation = cameraManager.getFirstPersonBodyCompensation();
            poseStack.pushPose();
            poseStack.translate(compensation.x, compensation.y, compensation.z);
            eclipse$bodyCompensationPushed = true;
            LivingEntityRenderer<?, ?, ?> renderer = (LivingEntityRenderer<?, ?, ?>) (Object) this;
            if (renderer.getModel() instanceof PlayerModel model) {
                if (!eclipse$debugPrinted) {
                    eclipse$debugPrinted = true;
                    System.out.println("[ECLIPSE-DEBUG-MODEL] Left arm cubes count: " + ((ModelPartAccessor) (Object) model.leftArm).getCubes().size());
                    ((ModelPartAccessor) (Object) model.leftArm).getChildren().forEach((name, part) -> {
                        System.out.println("[ECLIPSE-DEBUG-MODEL]   Child " + name + " cubes count: " + ((ModelPartAccessor) (Object) part).getCubes().size());
                        for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).getCubes()) {
                            System.out.println("[ECLIPSE-DEBUG-MODEL]     Cube polygons length: " + cube.polygons.length);
                            for (int pIdx = 0; pIdx < cube.polygons.length; pIdx++) {
                                ModelPart.Polygon poly = cube.polygons[pIdx];
                                float sumY = 0.0f;
                                for (ModelPart.Vertex v : poly.vertices()) {
                                    sumY += v.y();
                                }
                                float avgY = sumY / poly.vertices().length;
                                System.out.println("[ECLIPSE-DEBUG-MODEL]       Poly " + pIdx + " avgY=" + avgY + ":");
                                for (int i = 0; i < poly.vertices().length; i++) {
                                    ModelPart.Vertex v = poly.vertices()[i];
                                    System.out.println("[ECLIPSE-DEBUG-MODEL]         Vertex " + i + ": U=" + v.u() + ", V=" + v.v());
                                }
                            }
                        }
                    });
                }
                eclipse$captureVisibility(model, true);
                cameraManager.setSubmittingFirstPersonPlayer(true);
                cameraManager.applyFirstPersonBodyPose(model);
            }
        } else if (eclipse$isLocalFirstPersonState(state)) {
            LivingEntityRenderer<?, ?, ?> renderer = (LivingEntityRenderer<?, ?, ?>) (Object) this;
            if (renderer.getModel() instanceof PlayerModel model) {
                eclipse$restoreHeadForNormalLocalRender(model, state);
            }
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
    private void eclipse$afterSubmit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (eclipse$isLocalFirstPersonState(state)) {
            SmartCameraManager cameraManager = SmartCameraManager.getInstance();
            LivingEntityRenderer<?, ?, ?> renderer = (LivingEntityRenderer<?, ?, ?>) (Object) this;
            if (renderer.getModel() instanceof PlayerModel model) {
                eclipse$restoreVisibility(model, state);
            }
            cameraManager.setSubmittingFirstPersonPlayer(false);
        }
        if (eclipse$bodyCompensationPushed) {
            poseStack.popPose();
            eclipse$bodyCompensationPushed = false;
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
    private void eclipse$captureVisibility(PlayerModel model, boolean firstPerson) {
        eclipse$snapshotActive = true;
        eclipse$firstPersonSnapshot = firstPerson;
        eclipse$headVisible = model.head.visible;
        eclipse$hatVisible = model.hat.visible;
        eclipse$headSkipDraw = model.head.skipDraw;
        eclipse$hatSkipDraw = model.hat.skipDraw;
        eclipse$bodyVisible = model.body.visible;
        eclipse$jacketVisible = model.jacket.visible;

        eclipse$bodyY = model.body.y;
        eclipse$bodyZ = model.body.z;
        eclipse$headY = model.head.y;
        eclipse$headZ = model.head.z;
        eclipse$leftArmY = model.leftArm.y;
        eclipse$leftArmZ = model.leftArm.z;
        eclipse$rightArmY = model.rightArm.y;
        eclipse$rightArmZ = model.rightArm.z;
    }

    @Unique
    private void eclipse$restoreVisibility(PlayerModel model, EntityRenderState state) {
        if (!eclipse$snapshotActive) {
            return;
        }

        if (eclipse$firstPersonSnapshot) {
            model.head.visible = eclipse$headVisible;
            model.hat.visible = eclipse$hatVisible;
            model.head.skipDraw = eclipse$headSkipDraw;
            model.hat.skipDraw = eclipse$hatSkipDraw;
            model.body.visible = eclipse$bodyVisible;
            model.jacket.visible = eclipse$jacketVisible;

            model.body.y = eclipse$bodyY;
            model.body.z = eclipse$bodyZ;
            model.head.y = eclipse$headY;
            model.head.z = eclipse$headZ;
            model.leftArm.y = eclipse$leftArmY;
            model.leftArm.z = eclipse$leftArmZ;
            model.rightArm.y = eclipse$rightArmY;
            model.rightArm.z = eclipse$rightArmZ;

            eclipse$snapshotActive = false;
            eclipse$firstPersonSnapshot = false;
            return;
        }

        if (state instanceof AvatarRenderState avatar) {
            model.head.visible = true;
            model.hat.visible = avatar.showHat;
            model.head.skipDraw = false;
            model.hat.skipDraw = false;
            model.body.visible = true;
            model.jacket.visible = avatar.showJacket;

            model.body.y = eclipse$bodyY;
            model.body.z = eclipse$bodyZ;
            model.head.y = eclipse$headY;
            model.head.z = eclipse$headZ;
            model.leftArm.y = eclipse$leftArmY;
            model.leftArm.z = eclipse$leftArmZ;
            model.rightArm.y = eclipse$rightArmY;
            model.rightArm.z = eclipse$rightArmZ;

            eclipse$snapshotActive = false;
            return;
        }

        model.head.visible = eclipse$headVisible;
        model.hat.visible = eclipse$hatVisible;
        model.head.skipDraw = eclipse$headSkipDraw;
        model.hat.skipDraw = eclipse$hatSkipDraw;
        model.body.visible = eclipse$bodyVisible;
        model.jacket.visible = eclipse$jacketVisible;

        model.body.y = eclipse$bodyY;
        model.body.z = eclipse$bodyZ;
        model.head.y = eclipse$headY;
        model.head.z = eclipse$headZ;
        model.leftArm.y = eclipse$leftArmY;
        model.leftArm.z = eclipse$leftArmZ;
        model.rightArm.y = eclipse$rightArmY;
        model.rightArm.z = eclipse$rightArmZ;

        eclipse$snapshotActive = false;
    }

    @Unique
    private void eclipse$restoreHeadForNormalLocalRender(PlayerModel model, EntityRenderState state) {
        model.head.visible = true;
        model.head.skipDraw = false;
        model.hat.skipDraw = false;
        model.hat.visible = !(state instanceof AvatarRenderState avatar) || avatar.showHat;
    }
}
