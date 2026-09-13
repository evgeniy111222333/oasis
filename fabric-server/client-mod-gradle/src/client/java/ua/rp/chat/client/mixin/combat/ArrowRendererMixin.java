package ua.rp.chat.client.mixin.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.projectile.DirectArrowGeometry;

/** Replaces the complete vanilla flying-arrow mesh with the direct Blockbench export. */
@Mixin(ArrowRenderer.class)
public abstract class ArrowRendererMixin {
    @Unique private ItemStack eclipse$arrowStack;
    @Unique private final ItemStackRenderState eclipse$arrowRenderState = new ItemStackRenderState();

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ArrowRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$submitCustomArrow(ArrowRenderState state, PoseStack stack,
                                           SubmitNodeCollector collector,
                                           CameraRenderState cameraState, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        Entity context = client.getCameraEntity();
        if (context == null) return;
        if (eclipse$arrowStack == null) {
            eclipse$arrowStack = new ItemStack(Items.ARROW);
            eclipse$arrowStack.set(DataComponents.ITEM_MODEL,
                    Identifier.fromNamespaceAndPath("eclipseclient", "embedded_arrow"));
        }
        client.getItemModelResolver().updateForNonLiving(eclipse$arrowRenderState,
                eclipse$arrowStack, ItemDisplayContext.NONE, context);
        if (eclipse$arrowRenderState.isEmpty()) return;

        stack.pushPose();
        stack.mulPose(Axis.YP.rotationDegrees(state.yRot - 90.0f));
        stack.mulPose(Axis.ZP.rotationDegrees(state.xRot));
        // The direct export points tip-to-tail along +Z. Map it to vanilla's local +X
        // flight axis, then centre and scale the untouched Blockbench coordinates.
        stack.mulPose(Axis.YP.rotationDegrees(90.0f));
        stack.scale(DirectArrowGeometry.MODEL_SCALE, DirectArrowGeometry.MODEL_SCALE,
                DirectArrowGeometry.MODEL_SCALE);
        stack.translate(-DirectArrowGeometry.SOURCE_CENTER_X,
                -DirectArrowGeometry.SOURCE_CENTER_Y,
                -DirectArrowGeometry.SOURCE_CENTER_Z);
        eclipse$arrowRenderState.submit(stack, collector, state.lightCoords, 0, state.outlineColor);
        stack.popPose();
        ci.cancel();
    }
}
