package ua.rp.chat.client.mixin.appearance;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.appearance.EclipseAppearanceManager;
import ua.rp.chat.client.blood.BloodSkinTextureManager;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void eclipse$getAppearanceSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        PlayerSkin skin = EclipseAppearanceManager.getSkin(player.getUUID());
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void eclipse$composeBloodIntoSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        cir.setReturnValue(BloodSkinTextureManager.apply(player.getUUID(), cir.getReturnValue()));
    }
}
