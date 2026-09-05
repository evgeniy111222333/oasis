package ua.rp.chat.client.mixin.chat;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla/system chat is intentionally never buffered or drawn; RP feed has its own trusted transport. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addClientSystemMessage", at = @At("HEAD"), cancellable = true)
    private void eclipse$discardClientSystemMessage(Component message, CallbackInfo ci) { ci.cancel(); }

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"), cancellable = true)
    private void eclipse$discardServerSystemMessage(Component message, CallbackInfo ci) { ci.cancel(); }

    @Inject(method = "addPlayerMessage", at = @At("HEAD"), cancellable = true)
    private void eclipse$discardPlayerMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) { ci.cancel(); }
}
