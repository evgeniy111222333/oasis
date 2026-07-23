package ua.rp.chat.client.crawling;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CrawlingClientState {
    private static KeyMapping crawlKey;
    private static boolean localCrawling = false;
    private static boolean localStealth = false;
    private static float localProgress = 0.0f;
    private static long lastStateSendTime = 0L;

    private static final Map<UUID, RemoteCrawlInfo> REMOTE_STATES = new HashMap<>();

    public record RemoteCrawlInfo(boolean crawling, boolean stealth, float progress, long lastUpdated) {}

    private CrawlingClientState() {}

    public static void init() {
        crawlKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.crawl",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                KeyMapping.Category.GAMEPLAY
        ));
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return;

        Player player = client.player;

        // Handle C key press
        if (crawlKey != null) {
            while (crawlKey.consumeClick()) {
                if (localCrawling) {
                    if (canStandUp(player)) {
                        localCrawling = false;
                    } else {
                        client.gui.setOverlayMessage(Component.literal("Занадто низько, щоб підвестися!"), false);
                    }
                } else {
                    localCrawling = true;
                }
                syncState(true);
            }
        }

        // Auto-crawl if forced into low space
        if (!localCrawling && isCeilingLow(player)) {
            localCrawling = true;
            syncState(true);
        }

        // Smooth transition progress
        float targetProgress = localCrawling ? 1.0f : 0.0f;
        if (Math.abs(localProgress - targetProgress) > 0.001f) {
            localProgress += (targetProgress - localProgress) * 0.2f;
            if (Math.abs(localProgress - targetProgress) < 0.005f) {
                localProgress = targetProgress;
            }
        }

        // Check stealth mode (holding shift while crawling)
        boolean currentStealth = localCrawling && player.isShiftKeyDown();
        if (currentStealth != localStealth) {
            localStealth = currentStealth;
            syncState(true);
        }

        // Force swimming pose on local player if crawling
        if (localProgress > 0.1f) {
            player.setPose(Pose.SWIMMING);
        }

        // Periodic sync safeguard
        long now = System.currentTimeMillis();
        if (localCrawling && now - lastStateSendTime > 2000L) {
            syncState(false);
        }

        // Cleanup stale remote states
        REMOTE_STATES.entrySet().removeIf(e -> now - e.getValue().lastUpdated > 10000L);
    }

    public static void toggleCrawling(Minecraft client) {
        if (client == null || client.player == null) return;
        if (localCrawling) {
            if (canStandUp(client.player)) {
                localCrawling = false;
            } else {
                client.gui.setOverlayMessage(Component.literal("Занадто низько, щоб підвестися!"), false);
            }
        } else {
            localCrawling = true;
        }
        syncState(true);
    }

    public static boolean canStandUp(Player player) {
        if (player == null || player.level() == null) return true;
        AABB standingBox = new AABB(
                player.getX() - 0.3, player.getY(), player.getZ() - 0.3,
                player.getX() + 0.3, player.getY() + 1.8, player.getZ() + 0.3
        );
        return player.level().noCollision(player, standingBox);
    }

    private static boolean isCeilingLow(Player player) {
        if (player == null || player.level() == null) return false;
        AABB checkArea = new AABB(
                player.getX() - 0.2, player.getY() + 1.2, player.getZ() - 0.2,
                player.getX() + 0.2, player.getY() + 1.8, player.getZ() + 0.2
        );
        return !player.level().noCollision(player, checkArea);
    }

    private static void syncState(boolean immediate) {
        if (!ClientPlayNetworking.canSend(CrawlStatePayload.TYPE)) return;
        ClientPlayNetworking.send(new CrawlStatePayload(localCrawling, localStealth, localProgress));
        lastStateSendTime = System.currentTimeMillis();
    }

    public static void handleRemoteSync(UUID playerUuid, boolean crawling, boolean stealth, float progress) {
        REMOTE_STATES.put(playerUuid, new RemoteCrawlInfo(crawling, stealth, progress, System.currentTimeMillis()));
    }

    public static boolean isCrawling() {
        return localCrawling;
    }

    public static boolean isCrawling(Player player) {
        if (player == null) return false;
        Minecraft client = Minecraft.getInstance();
        if (client != null && player.equals(client.player)) {
            return localCrawling;
        }
        RemoteCrawlInfo info = REMOTE_STATES.get(player.getUUID());
        return info != null ? info.crawling : player.getPose() == Pose.SWIMMING;
    }

    public static float getCrawlProgress() {
        return localProgress;
    }

    public static float getCrawlProgress(Player player) {
        if (player == null) return 0.0f;
        Minecraft client = Minecraft.getInstance();
        if (client != null && player.equals(client.player)) {
            return localProgress;
        }
        RemoteCrawlInfo info = REMOTE_STATES.get(player.getUUID());
        return info != null ? info.progress : (player.getPose() == Pose.SWIMMING ? 1.0f : 0.0f);
    }

    public static boolean isStealth() {
        return localStealth;
    }

    public static boolean isStealth(Player player) {
        if (player == null) return false;
        Minecraft client = Minecraft.getInstance();
        if (client != null && player.equals(client.player)) {
            return localStealth;
        }
        RemoteCrawlInfo info = REMOTE_STATES.get(player.getUUID());
        return info != null && info.stealth;
    }
}
