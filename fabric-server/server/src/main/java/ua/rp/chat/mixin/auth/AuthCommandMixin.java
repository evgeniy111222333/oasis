package ua.rp.chat.mixin.auth;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.RPChat;

import java.util.Locale;

@Mixin(Commands.class)
public abstract class AuthCommandMixin {
    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockCommandsBeforeAuthentication(CommandSourceStack source, String command,
                                                            CallbackInfo callback) {
        ServerPlayer player = source.getPlayer();
        RPChat plugin = RPChat.getInstance();
        if (player == null || plugin == null || plugin.getAuthManager() == null
                || !plugin.getAuthManager().isPendingAuth(player.getUUID())) {
            return;
        }
        String normalized = command == null ? "" : command.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        String root = normalized.split("\\s+", 2)[0];
        if (root.equals("login") || root.equals("register") || root.equals("l")) return;
        source.sendFailure(Component.literal("Спочатку авторизуйтеся."));
        callback.cancel();
    }
}
