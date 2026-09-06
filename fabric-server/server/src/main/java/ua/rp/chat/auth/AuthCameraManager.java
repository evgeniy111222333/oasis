package ua.rp.chat.auth;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthCameraManager {

    private final RPChat plugin;

    // Camera locations per player
    private final Map<UUID, AuthLocation> cameraLocations = new ConcurrentHashMap<>();

    // Auth scene configuration (scenic viewing camera position)
    private static final double CAM_X = -3.0;
    private static final double CAM_Y = 100.8;
    private static final double CAM_Z = 5.0;
    private static final float CAM_YAW = -40.0f;
    private static final float CAM_PITCH = 2.0f;

    public AuthCameraManager(RPChat plugin) {
        this.plugin = plugin;
    }

    public void setupCinematicView(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();

        // Calculate position
        AuthLocation cameraLocation = new AuthLocation(world, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);

        // Store camera location for position enforcement
        cameraLocations.put(player.getUUID(), cameraLocation);

        // Teleport player (camera) to the viewing position
        RPChat.teleport(player, world, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);
    }

    public void tickCameraLocks() {
        cameraLocations.forEach((uuid, expected) -> {
            ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(uuid);
            if (player == null) {
                cameraLocations.remove(uuid);
                return;
            }

            Vec3 current = player.position();
            double distSq = current.distanceToSqr(expected.x(), expected.y(), expected.z());
            if (distSq > 1.0 || !((ServerLevel) player.level()).equals(expected.level())) {
                RPChat.teleport(player, expected.level(), expected.x(), expected.y(), expected.z(), expected.yaw(), expected.pitch());
            }
        });
    }

    public void cleanup(ServerPlayer player) {
        cameraLocations.remove(player.getUUID());
    }

    public AuthLocation getCameraLocation(UUID uuid) {
        return cameraLocations.get(uuid);
    }

    public boolean hasCameraLock(UUID uuid) {
        return cameraLocations.containsKey(uuid);
    }
}
