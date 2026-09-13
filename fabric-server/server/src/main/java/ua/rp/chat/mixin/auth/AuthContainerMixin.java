package ua.rp.chat.mixin.auth;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class AuthContainerMixin {
    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockMenusDuringAuthentication(
            MenuProvider provider,
            CallbackInfoReturnable<OptionalInt> callback) {
        RPChat plugin = RPChat.getInstance();
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (plugin != null && plugin.getAuthManager() != null
                && plugin.getAuthManager().isPendingAuth(player.getUUID())) {
            callback.setReturnValue(OptionalInt.empty());
        }
    }
}
