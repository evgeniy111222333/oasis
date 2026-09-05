package ua.rp.chat.client.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.pickup.GroundedLootRenderer;

/** Keeps vanilla shadows/name handling but replaces only the bobbing item-model submit. */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void eclipse$captureRenderedItem(ItemEntity item, ItemEntityRenderState state, float tickDelta,
                                             CallbackInfo ci) {
        GroundedLootRenderer.captureEntity(state, item);
    }

    @Redirect(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V")
    )
    private static void eclipse$submitGroundedLoot(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                                   ItemClusterRenderState clusterState, RandomSource random,
                                                   AABB modelBounds) {
        if (!(clusterState instanceof ItemEntityRenderState state)) return;
        ItemEntity item = GroundedLootRenderer.entityFor(state);
        if (item == null || !item.isAlive()) return;

        GroundedLootRenderer.undoVanillaHoverAndSpin(state, poseStack, modelBounds);
        GroundedLootRenderer.submit(item, state, poseStack, collector, light);
    }
}
