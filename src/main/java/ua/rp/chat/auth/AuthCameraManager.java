package ua.rp.chat.auth;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the cinematic camera view and NPC skin projection during auth.
 * 
 * Spawns a client-side-only NPC using the player's own skin/cape via NMS packets,
 * positions camera to show the NPC on the left looking into the distance,
 * and the auth menu appears on the right.
 */
public class AuthCameraManager {

    private final JavaPlugin plugin;

    // Camera locations per player
    private final Map<UUID, Location> cameraLocations = new ConcurrentHashMap<>();

    // Auth scene configuration (scenic viewing camera position)
    private static final double CAM_X = -3.0;
    private static final double CAM_Y = 100.8;
    private static final double CAM_Z = 5.0;
    private static final float CAM_YAW = -40.0f;
    private static final float CAM_PITCH = 2.0f;

    public AuthCameraManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sets up the complete cinematic view: teleports player to the camera position.
     */
    public void setupCinematicView(Player player) {
        World world = player.getWorld();

        // Calculate position
        Location cameraLocation = new Location(world, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);

        // Store camera location for position enforcement
        cameraLocations.put(player.getUniqueId(), cameraLocation.clone());

        // Teleport player (camera) to the viewing position
        player.teleport(cameraLocation);

        // Start camera lock task (keeps camera fixed)
        startCameraLock(player);
    }

    /**
     * Starts a repeating task that locks the player's camera position.
     * If the player moves, they're silently teleported back.
     */
    private void startCameraLock(Player player) {
        UUID uuid = player.getUniqueId();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !cameraLocations.containsKey(uuid)) {
                    cancel();
                    return;
                }

                Location expected = cameraLocations.get(uuid);
                Location current = player.getLocation();

                // Check if player moved too far from camera point
                double distSq = current.distanceSquared(expected);
                if (distSq > 1.0) {
                    player.teleport(expected);
                }
            }
        }.runTaskTimer(plugin, 10L, 5L); // Check every 5 ticks
    }

    /**
     * Cleans up camera state for a player.
     */
    public void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        cameraLocations.remove(uuid);
    }

    /**
     * Gets the locked camera location for a player (if in auth).
     */
    public Location getCameraLocation(UUID uuid) {
        return cameraLocations.get(uuid);
    }

    /**
     * Checks if a player currently has an active camera lock.
     */
    public boolean hasCameraLock(UUID uuid) {
        return cameraLocations.containsKey(uuid);
    }
}
