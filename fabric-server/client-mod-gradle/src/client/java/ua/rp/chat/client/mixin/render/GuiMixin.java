package ua.rp.chat.client.mixin.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.carver.CarverClientState;

/**
 * The carver drafting screen resolves cells through the free mouse pointer, while the vanilla
 * crosshair is still painted at the camera's forward point (the centre of the lifted hologram).
 * That leftover reticle reads as a second cursor that selects a different voxel than the one
 * under the pointer, so it is suppressed for the whole drafting session. The work-phase crosshair
 * is left untouched: there the camera is deliberately aimed at the strike contact.
 */
@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$hideCrosshairWhileDesigning(GuiGraphicsExtractor graphics,
                                                     DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CarverClientState.designing()) {
            ci.cancel();
        }
    }
}
