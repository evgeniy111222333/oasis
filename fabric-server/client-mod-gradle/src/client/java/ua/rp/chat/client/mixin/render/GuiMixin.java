package ua.rp.chat.client.mixin.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.carver.CarverClientState;
import ua.rp.chat.client.mcef.McefGlStateGuard;

/**
 * The carver drafting screen resolves cells through the free mouse pointer, while the vanilla
 * crosshair is still painted at the camera's forward point (the centre of the lifted hologram).
 * That leftover reticle reads as a second cursor that selects a different voxel than the one
 * under the pointer, so it is suppressed for the whole drafting session. The work-phase crosshair
 * is left untouched: there the camera is deliberately aimed at the strike contact.
 */
@Mixin(Gui.class)
public class GuiMixin {
    /**
     * MCEF's message loop runs at the head of {@code GameRenderer.render} and its offscreen paint
     * leaves the GL texture/unpack state dirty. This runs after that paint and before the frame's
     * GUI pass is built (both the HUD and any open screen are extracted on top of this call), so
     * the embedded browser can never make Minecraft text render as solid bars.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"))
    private void eclipse$restoreGlStateAfterMcef(GuiGraphicsExtractor graphics,
                                                 DeltaTracker deltaTracker, CallbackInfo ci) {
        McefGlStateGuard.restore();
    }

    @Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$hideCrosshairWhileDesigning(GuiGraphicsExtractor graphics,
                                                     DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CarverClientState.designing()) {
            ci.cancel();
        }
    }
}
