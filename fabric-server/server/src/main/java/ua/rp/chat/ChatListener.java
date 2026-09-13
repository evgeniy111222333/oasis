package ua.rp.chat;

import net.minecraft.server.level.ServerPlayer;

public class ChatListener {
    private final RPChat plugin;

    public ChatListener(RPChat plugin) {
        this.plugin = plugin;
    }

    public boolean onChat(ServerPlayer sender, String rawMessage) {
        // Skip if player is not authenticated
        if (plugin.getAuthManager() != null && plugin.getAuthManager().isPendingAuth(sender.getUUID())) {
            return false; // cancel
        }

        rawMessage = rawMessage.trim();
        if (rawMessage.isBlank()) {
            return false;
        }

        RpChatChannel channel = RpChatChannel.SPEAK;
        if (rawMessage.startsWith("!") && rawMessage.length() > 1) {
            channel = RpChatChannel.SHOUT;
            rawMessage = rawMessage.substring(1).trim();
        } else if ((rawMessage.startsWith("~") || rawMessage.startsWith(".")) && rawMessage.length() > 1) {
            channel = RpChatChannel.WHISPER;
            rawMessage = rawMessage.substring(1).trim();
        }
        
        if (!rawMessage.isBlank()) {
            String finalMessage = rawMessage;
            RpChatChannel finalChannel = channel;
            plugin.getServer().execute(() -> {
                if (sender.connection != null) {
                    plugin.getRpChatService().sendSpeech(sender, finalChannel, finalMessage);
                }
            });
        }
        return false; // cancel default chat broadcast
    }
}
