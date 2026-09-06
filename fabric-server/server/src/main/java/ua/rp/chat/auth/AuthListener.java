package ua.rp.chat.auth;

import net.minecraft.server.level.ServerPlayer;

public class AuthListener {
    private final AuthManager authManager;

    public AuthListener(AuthManager authManager) {
        this.authManager = authManager;
    }

    public boolean isPending(ServerPlayer player) {
        if (player == null) return false;
        return authManager.isPendingAuth(player.getUUID());
    }

    public void onJoin(ServerPlayer player) {
        authManager.handleJoin(player);
    }

    public void onQuit(ServerPlayer player) {
        authManager.broadcastRoleplayQuit(player);
        authManager.handleQuit(player);
    }
}
