package ua.rp.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

        // Format message: replace "_" in username with space for RP styling
        final String rpName = sender.getName().replace("_", " ");
        
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            return Component.text()
                .append(Component.text("[Локальний] ", NamedTextColor.GRAY))
                .append(Component.text(rpName, NamedTextColor.WHITE))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(message.color(NamedTextColor.WHITE))
                .build();
        });
    }
}
