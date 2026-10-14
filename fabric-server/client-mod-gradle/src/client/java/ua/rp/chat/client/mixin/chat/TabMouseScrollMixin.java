package ua.rp.chat.client.mixin.chat;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.tab.EclipseTabRenderer;

@Mixin(MouseHandler.class)
public class TabMouseScrollMixin {
    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void eclipse$scrollRoster(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && EclipseTabRenderer.scroll(vertical)) ci.cancel();
    }
}
