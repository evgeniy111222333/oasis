package ua.rp.chat.auth;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.entity.Player;

/**
 * Blocks all gameplay actions for players who haven't authenticated yet.
 * Only allows /login, /register, and /l commands through.
 */
public class AuthListener implements Listener {

    private static final TextColor TERRACOTTA = TextColor.color(0xE3A899);
    private static final TextColor PEBBLE_GRAY = TextColor.color(0xB0A8A0);

    private final AuthManager authManager;

    public AuthListener(AuthManager authManager) {
        this.authManager = authManager;
    }

    // --- Join / Quit ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinSetup(PlayerJoinEvent event) {
        authManager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(null);
        authManager.broadcastRoleplayQuit(player);
        authManager.handleQuit(player);
    }

    // --- Block all chat from unauthenticated players ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                Component.text("Спочатку авторизуйтесь.", TERRACOTTA)
            );
        }
    }

    // --- Block commands except /login, /register, /l ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            return;
        }

        String msg = event.getMessage().toLowerCase().trim();

        // Allow auth commands
        if (msg.startsWith("/login ") || msg.startsWith("/register ") ||
            msg.startsWith("/l ") || msg.equals("/login") || msg.equals("/register") || msg.equals("/l")) {
            return;
        }

        // Block everything else
        event.setCancelled(true);
        event.getPlayer().sendMessage(
            Component.text("Спочатку авторизуйтесь.", TERRACOTTA)
        );
    }

    // --- Block movement ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            // Only cancel if actual position changed (not just looking)
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                event.setCancelled(true);
            }
        }
    }

    // --- Block all interactions ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            if (authManager.isPendingAuth(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // --- Block damage ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (authManager.isPendingAuth(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            if (authManager.isPendingAuth(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // --- Block item operations ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (authManager.isPendingAuth(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
