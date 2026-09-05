package ua.rp.chat.client.mixin.camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Look lock for the work bench: mouse deltas are consumed during the render
 * phase, after every client tick, so re-setting yaw/pitch per tick can never
 * hold. Cancelling the turn at the source instead freezes the artisan facing
 * the workpiece while the orbit camera keeps full right-drag control.
 */
@Mixin(net.minecraft.client.MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void eclipse$lockWorkLook(double timeDelta, CallbackInfo ci) {
        try {
            if (ua.rp.chat.client.carver.CarverClientState.working()) {
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
        }
    }
}
