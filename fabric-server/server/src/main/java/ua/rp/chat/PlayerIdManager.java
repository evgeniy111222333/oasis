package ua.rp.chat;

import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerIdManager {
    private final Map<UUID, Integer> playerIds = new HashMap<>();
    private final boolean[] usedIds = new boolean[1001]; // Support up to 1000 players

    public synchronized int getOrAssignId(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (playerIds.containsKey(uuid)) {
            return playerIds.get(uuid);
        }
        // Find smallest free ID starting from 1
        for (int i = 1; i < usedIds.length; i++) {
            if (!usedIds[i]) {
                usedIds[i] = true;
                playerIds.put(uuid, i);
                return i;
            }
        }
        return -1; // Fallback
    }

    public synchronized void releaseId(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (playerIds.containsKey(uuid)) {
            int id = playerIds.remove(uuid);
            if (id >= 1 && id < usedIds.length) {
                usedIds[id] = false;
            }
        }
    }

    public synchronized int getId(ServerPlayer player) {
        return playerIds.getOrDefault(player.getUUID(), -1);
    }
}
