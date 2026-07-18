package ua.rp.chat.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.heavyhammer.HeavyHammerClientState;

@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void eclipse$enforceSingleHammerOwner(ArmedEntityRenderState state,
                                                  ItemStackRenderState itemState,
                                                  ItemStack stack,
                                                  HumanoidArm arm,
                                                  PoseStack poseStack,
                                                  SubmitNodeCollector collector,
                                                  int light,
                                                  CallbackInfo ci) {
        if (HeavyHammerClientState.suppressVanillaHammer(stack)) {
            ci.cancel();
            return;
        }
        if (!(state instanceof AvatarRenderState avatar) || arm != state.mainArm) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        Entity entity = client.level.getEntity(avatar.id);
        if (entity instanceof Player player && HeavyHammerClientState.suppressSelectedItem(player)) {
            ci.cancel();
        }
    }
}
