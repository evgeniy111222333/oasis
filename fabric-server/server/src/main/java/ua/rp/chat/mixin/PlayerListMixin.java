package ua.rp.chat.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$suppressVanillaJoinAndQuit(Component message, boolean overlay, CallbackInfo callback) {
        if (message.getContents() instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            if (key.equals("multiplayer.player.joined") || key.equals("multiplayer.player.left")) {
                callback.cancel();
            }
        }
    }
}
