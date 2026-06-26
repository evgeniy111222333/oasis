package ua.rp.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.Set;

public class ChatListener implements Listener {
    private final RPChat plugin;
    private final double chatRadius = 20.0;

    public ChatListener(RPChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getIdManager().getOrAssignId(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getIdManager().releaseId(event.getPlayer());
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

        // Format message: replace "_" with space, get session ID
        final String rpName = sender.getName().replace("_", " ");
        final int id = plugin.getIdManager().getId(sender);
        
        // Colors
        final TextColor nameColor = TextColor.color(0xE8C58C);    // Soft Gold
        final TextColor idColor = TextColor.color(0xA9A9A9);      // Dark Gray
        final TextColor connectorColor = TextColor.color(0xD3D3D3); // Light Gray
        final TextColor textColor = TextColor.color(0xFFFFFF);      // White

        event.renderer((source, sourceDisplayName, message, viewer) -> {
            return Component.text()
                .append(Component.text(rpName, nameColor))
                .append(Component.text(" [", connectorColor))
                .append(Component.text(id, idColor))
                .append(Component.text("]", connectorColor))
                .append(Component.text(" каже: ", connectorColor))
                .append(Component.text("«", textColor))
                .append(message.color(textColor))
                .append(Component.text("»", textColor))
                .build();
        });
    }
}
