package ua.rp.chat.crawling;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import ua.rp.chat.antiabuse.ActionRateLimiter;
import ua.rp.chat.client.crawling.CrawlStatePayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Server authority for the crawling/prone pose. The pose is client-driven for feel, but every
 * state change is gated on authentication, rate-limited and clamped, and the per-player state is
 * cleaned on disconnect so an offline-name relog can never inherit a stale pose.
 */
public final class CrawlingServerManager {
    private static final Map<UUID, PlayerCrawlState> STATES = new ConcurrentHashMap<>();
    /** Per-player packet budget: crawl state is cosmetic, so a tight cap is enough. */
    private static final ActionRateLimiter CRAWL_RATE = new ActionRateLimiter(20.0, 20.0);
    /** Pending-auth predicate wired from RPChat: an unauthenticated player must not mutate pose. */
    private static volatile Predicate<ServerPlayer> pendingAuth = player -> false;

    public record PlayerCrawlState(boolean crawling, boolean stealth, float progress, long updatedAt) {}

    private CrawlingServerManager() {}

    public static void init(Predicate<ServerPlayer> isPendingAuth) {
        if (isPendingAuth != null) pendingAuth = isPendingAuth;
        PayloadTypeRegistry.serverboundPlay().register(CrawlStatePayload.TYPE, CrawlStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CrawlStatePayload.TYPE, CrawlStatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CrawlStatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleClientCrawlState(player, payload));
        });

        // Clean the static state map: a disconnected player must not leave a swimming pose that a
        // same-name (offline UUID) relog would otherwise inherit before sending any packet.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) STATES.remove(player.getUUID());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crawl").executes(ctx -> commandToggle(ctx.getSource().getPlayer())));
            dispatcher.register(Commands.literal("prone").executes(ctx -> commandToggle(ctx.getSource().getPlayer())));
            dispatcher.register(Commands.literal("lay").executes(ctx -> commandToggle(ctx.getSource().getPlayer())));
        });
    }

    private static int commandToggle(ServerPlayer player) {
        if (player != null && !pendingAuth.test(player)) toggleCrawl(player);
        return 1;
    }

    public static void handleClientCrawlState(ServerPlayer player, CrawlStatePayload payload) {
        if (player == null || pendingAuth.test(player)) return;
        if (!CRAWL_RATE.tryAcquire(player.getUUID())) return;
        UUID uuid = player.getUUID();
        PlayerCrawlState previous = STATES.get(uuid);

        boolean crawling = payload.crawling();
        boolean stealth = payload.stealth();
        // Clamp before anything is broadcast: a crafted NaN/Infinity/huge progress must never
        // reach nearby clients or the stored state.
        float progress = payload.progress();
        if (!Float.isFinite(progress)) progress = 0.0f;
        progress = Math.max(0.0f, Math.min(1.0f, progress));

        if (crawling) {
            player.setPose(Pose.SWIMMING);
        } else if (previous != null && previous.crawling()) {
            if (!hasStandingClearance(player)) {
                player.sendSystemMessage(Component.literal("Занадто низько, щоб підвестися!"), true);
                ServerPlayNetworking.send(player, new CrawlStatePayload(true, stealth, 1.0f));
                return;
            }
            player.setPose(Pose.STANDING);
        }

        STATES.put(uuid, new PlayerCrawlState(crawling, stealth, progress, System.currentTimeMillis()));

        CrawlStatePayload broadcastPayload = new CrawlStatePayload(crawling, stealth, progress);
        ServerLevel level = (ServerLevel) player.level();
        for (ServerPlayer other : level.players()) {
            if (other != player && other.distanceToSqr(player) < 16384.0
                    && ServerPlayNetworking.canSend(other, CrawlStatePayload.TYPE)) {
                ServerPlayNetworking.send(other, broadcastPayload);
            }
        }
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
            } else {
                player.sendSystemMessage(Component.literal("Занадто низько, щоб підвестися!"), true);
            }
        } else {
            player.setPose(Pose.SWIMMING);
            STATES.put(uuid, new PlayerCrawlState(true, false, 1.0f, System.currentTimeMillis()));
            ServerPlayNetworking.send(player, new CrawlStatePayload(true, false, 1.0f));
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
