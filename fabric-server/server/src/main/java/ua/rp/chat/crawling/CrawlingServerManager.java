package ua.rp.chat.crawling;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import ua.rp.chat.client.crawling.CrawlStatePayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CrawlingServerManager {
    private static final Map<UUID, PlayerCrawlState> STATES = new HashMap<>();

    public record PlayerCrawlState(boolean crawling, boolean stealth, float progress, long updatedAt) {}

    private CrawlingServerManager() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(CrawlStatePayload.TYPE, CrawlStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CrawlStatePayload.TYPE, CrawlStatePayload.CODEC);
        System.out.println("[CRAWL-DEBUG-SERVER] Registered CrawlStatePayload codecs on server");

        ServerPlayNetworking.registerGlobalReceiver(CrawlStatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleClientCrawlState(player, payload));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crawl")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player != null) {
                            System.out.println("[CRAWL-DEBUG-SERVER] Command /crawl executed by " + player.getName().getString());
                            toggleCrawl(player);
                        }
                        return 1;
                    }));
            dispatcher.register(Commands.literal("prone")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player != null) {
                            System.out.println("[CRAWL-DEBUG-SERVER] Command /prone executed by " + player.getName().getString());
                            toggleCrawl(player);
                        }
                        return 1;
                    }));
            dispatcher.register(Commands.literal("lay")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player != null) {
                            System.out.println("[CRAWL-DEBUG-SERVER] Command /lay executed by " + player.getName().getString());
                            toggleCrawl(player);
                        }
                        return 1;
                    }));
        });
        System.out.println("[CRAWL-DEBUG-SERVER] Registered commands /crawl, /prone, /lay");
    }

    public static void handleClientCrawlState(ServerPlayer player, CrawlStatePayload payload) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        PlayerCrawlState previous = STATES.get(uuid);

        System.out.println("[CRAWL-DEBUG-SERVER] Received CrawlStatePayload from " + player.getName().getString()
                + " -> crawling=" + payload.crawling() + ", stealth=" + payload.stealth() + ", progress=" + payload.progress());

        if (payload.crawling()) {
            player.setPose(Pose.SWIMMING);
        } else if (previous != null && previous.crawling() && !payload.crawling()) {
            // Check ceiling clearance
            if (!hasStandingClearance(player)) {
                player.sendSystemMessage(Component.literal("Занадто низько, щоб підвестися!"), true);
                System.out.println("[CRAWL-DEBUG-SERVER] Player " + player.getName().getString() + " cannot stand up due to ceiling");
                ServerPlayNetworking.send(player, new CrawlStatePayload(true, payload.stealth(), 1.0f));
                return;
            }
            player.setPose(Pose.STANDING);
        }

        STATES.put(uuid, new PlayerCrawlState(payload.crawling(), payload.stealth(), payload.progress(), System.currentTimeMillis()));

        // Broadcast to tracking players
        CrawlStatePayload broadcastPayload = new CrawlStatePayload(payload.crawling(), payload.stealth(), payload.progress());
        ServerLevel level = (ServerLevel) player.level();
        int count = 0;
        for (ServerPlayer other : level.players()) {
            if (other != player && other.distanceToSqr(player) < 16384.0) {
                if (ServerPlayNetworking.canSend(other, CrawlStatePayload.TYPE)) {
                    ServerPlayNetworking.send(other, broadcastPayload);
                    count++;
                }
            }
        }
        System.out.println("[CRAWL-DEBUG-SERVER] Broadcasted crawl state for " + player.getName().getString() + " to " + count + " nearby players");
    }

    public static void toggleCrawl(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        PlayerCrawlState current = STATES.get(uuid);
        boolean isCrawling = current != null && current.crawling();

        if (isCrawling) {
            if (hasStandingClearance(player)) {
                player.setPose(Pose.STANDING);
                STATES.put(uuid, new PlayerCrawlState(false, false, 0.0f, System.currentTimeMillis()));
                ServerPlayNetworking.send(player, new CrawlStatePayload(false, false, 0.0f));
                System.out.println("[CRAWL-DEBUG-SERVER] Toggled off crawl mode for " + player.getName().getString());
            } else {
                player.sendSystemMessage(Component.literal("Занадто низько, щоб підвестися!"), true);
                System.out.println("[CRAWL-DEBUG-SERVER] Blocked toggle off crawl mode for " + player.getName().getString() + " (ceiling low)");
            }
        } else {
            player.setPose(Pose.SWIMMING);
            STATES.put(uuid, new PlayerCrawlState(true, false, 1.0f, System.currentTimeMillis()));
            ServerPlayNetworking.send(player, new CrawlStatePayload(true, false, 1.0f));
            System.out.println("[CRAWL-DEBUG-SERVER] Toggled on crawl mode for " + player.getName().getString());
        }
    }

    private static boolean hasStandingClearance(ServerPlayer player) {
        if (player == null || player.level() == null) return true;
        net.minecraft.world.phys.AABB standingBox = new net.minecraft.world.phys.AABB(
                player.getX() - 0.3, player.getY(), player.getZ() - 0.3,
                player.getX() + 0.3, player.getY() + 1.8, player.getZ() + 0.3
        );
        return player.level().noCollision(player, standingBox);
    }

    public static boolean isCrawling(ServerPlayer player) {
        if (player == null) return false;
        PlayerCrawlState state = STATES.get(player.getUUID());
        return state != null && state.crawling();
    }

    public static void onPlayerQuit(ServerPlayer player) {
        if (player != null) STATES.remove(player.getUUID());
    }
}
