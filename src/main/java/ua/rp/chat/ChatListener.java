package ua.rp.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {
    private final RPChat plugin;

    public ChatListener(RPChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();

        // Skip if player is not authenticated (AuthListener handles the block)
        if (plugin.getAuthManager() != null && plugin.getAuthManager().isPendingAuth(sender.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (rawMessage.isBlank()) {
            return;
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
            RpChatChannel finalChannel = channel;
            String finalMessage = rawMessage;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sender.isOnline()) {
                    plugin.getRpChatService().sendSpeech(sender, finalChannel, finalMessage);
                }
            });
        }
    }
}
