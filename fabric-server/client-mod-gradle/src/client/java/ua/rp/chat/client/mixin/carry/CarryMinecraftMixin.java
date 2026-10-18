package ua.rp.chat.client.mixin.carry;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.carry.CarryClient;
import ua.rp.chat.client.carry.CarryClientState;

/** Carry intent is handled before existing carving and vanilla interactions. */
@Mixin(value = Minecraft.class, priority = 1100)
public abstract class CarryMinecraftMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void eclipse$carryUse(CallbackInfo ci) {
        if (CarryClient.handleUse((Minecraft) (Object) this)) ci.cancel();
    }
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void eclipse$carryAttack(CallbackInfoReturnable<Boolean> cir) {
        if (CarryClient.suppressAttack()) cir.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void eclipse$carryContinueAttack(boolean down, CallbackInfo ci) {
        if (CarryClient.supported() && (CarryClientState.twoHands() || CarryClient.placing())) ci.cancel();
    }
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void eclipse$carryFocusOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        var focus = CarryClient.focus();
        if (focus != null && focus.entity() == entity.getId()) cir.setReturnValue(true);
    }
}
