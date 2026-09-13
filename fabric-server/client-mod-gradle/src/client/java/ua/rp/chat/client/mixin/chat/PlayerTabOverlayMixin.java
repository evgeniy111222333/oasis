package ua.rp.chat.client.mixin.chat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.AcquaintanceClientState;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Inject(method = "setVisible", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockVanillaTabVisible(boolean visible, CallbackInfo ci) {
        AcquaintanceClientState.setTabRequested(visible);
        if (visible) {
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void eclipse$hideVanillaTab(GuiGraphicsExtractor graphics, int width, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        if (AcquaintanceClientState.isCustomTabVisible()) {
            ci.cancel();
        }
    }
}
