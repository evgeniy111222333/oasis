package ua.rp.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import java.util.Set;

public class ChatListener implements Listener {
    private final RPChat plugin;
    private final double chatRadius = 20.0;

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
        
        // Remove standard viewers
        Set<Audience> viewers = event.viewers();
        viewers.clear();
        
        // Add sender and console
        viewers.add(sender);
        viewers.add(plugin.getServer().getConsoleSender());

        // Find nearby players
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            if (onlinePlayer.getWorld().equals(sender.getWorld())) {
                if (onlinePlayer.getLocation().distance(sender.getLocation()) <= chatRadius) {
                    viewers.add(onlinePlayer);
                }
            }
        }

        // Format message: use registered RP name if present, fallback to username
        String nameVal = plugin.getAuthManager() != null ? plugin.getAuthManager().getRpName(sender.getUniqueId()) : null;
        if (nameVal == null) {
            nameVal = sender.getName().replace("_", " ");
        }
        final String rpName = nameVal;
        final int style = plugin.getActiveStyle();

        event.renderer((source, sourceDisplayName, message, viewer) -> {
            // Get raw message text
            String rawMessage = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
            return ChatFormatter.formatSpeech(rpName, rawMessage, style);
        });
    }
}
