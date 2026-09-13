package ua.rp.chat.client.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.carver.CarverBagRenderLayer;

/**
 * Hides ordinary hand items (scrolls, blocks, torches) while carving so they
 * do not clip through the artisan's active chisels.
 */
@Mixin(PlayerItemInHandLayer.class)
public class PlayerItemInHandLayerMixin {
    @Inject(
            method = "submitArmWithItem(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void eclipse$suppressHandItemsDuringCarverWork(
            AvatarRenderState state,
            ItemStackRenderState itemRenderState,
            ItemStack stack,
            HumanoidArm arm,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        if (client.level.getEntity(state.id) instanceof Player player) {
            if (CarverBagRenderLayer.getWorkTicks(player, client) >= 0.0) {
                ci.cancel();
            }
        }
    }
}
