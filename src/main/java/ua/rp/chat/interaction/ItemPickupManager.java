package ua.rp.chat.interaction;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.rp.chat.RPChat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces touch pickup with a deliberate interaction: aim at a dropped item
 * and use the main-hand right-click action while standing close to it.
 */
public final class ItemPickupManager implements Listener, PluginMessageListener {
    public static final String ACTION_CHANNEL = "rpchat:item_pickup";
    private final RPChat plugin;

    public ItemPickupManager(RPChat plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, ACTION_CHANNEL, this);
    }

    /** Never let proximity alone transfer an item to a player's inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRightClickEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Item item)) return;
        // A use action is sent for both hands in some client states. Process
        // the main-hand action once and suppress the item's default behaviour.
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;

        take(event.getPlayer(), item);
    }

    /** Receives the item UUID selected by the client's item-specific raycast. */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ACTION_CHANNEL.equals(channel) || player == null || message == null || message.length != 16) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            UUID itemId = new UUID(input.readLong(), input.readLong());
            if (input.available() != 0) return;
            if (player.getWorld().getEntity(itemId) instanceof Item item) take(player, item);
        } catch (IOException impossible) {
            plugin.getLogger().warning("Rejected malformed item pickup action from " + player.getName());
        }
    }

    private void take(Player player, Item item) {
        if (plugin.getAuthManager().isPendingAuth(player.getUniqueId()) || !item.isValid() || item.isDead()) return;
        double distanceSquared = player.getEyeLocation().distanceSquared(item.getLocation());
        if (!ItemPickupRules.mayPickUp(item.canPlayerPickup(), item.getPickupDelay(),
                item.getOwner(), player.getUniqueId(), distanceSquared) || !isLookingAt(player, item)) return;

        ItemStack offered = item.getItemStack();
        if (offered.getType().isAir() || offered.getAmount() <= 0) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(offered.clone());
        int remaining = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (remaining == offered.getAmount()) {
            player.sendActionBar(Component.text("Недостатньо місця в інвентарі."));
            return;
        }

        if (remaining == 0) {
            item.remove();
        } else {
            ItemStack remainder = offered.clone();
            remainder.setAmount(remaining);
            item.setItemStack(remainder);
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.0f);
    }

    /**
     * The UUID in the packet is only a hint. The server repeats the view ray
     * with block collision, so a client cannot collect an item through a wall
     * or select a different nearby item.
     */
    private static boolean isLookingAt(Player player, Item expected) {
        Vector direction = player.getEyeLocation().getDirection();
        if (direction.lengthSquared() <= 0.0) return false;
        RayTraceResult hit = player.getWorld().rayTrace(
                player.getEyeLocation(), direction.normalize(), ItemPickupRules.MAX_INTERACTION_DISTANCE,
                FluidCollisionMode.NEVER, true, 0.14,
                entity -> entity instanceof Item && entity.isValid());
        return hit != null && expected.equals(hit.getHitEntity());
    }
}
