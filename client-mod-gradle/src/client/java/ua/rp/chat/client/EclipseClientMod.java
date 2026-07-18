package ua.rp.chat.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ua.rp.chat.client.camera.EclipseHudOverlay;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.vitals.VitalsClientState;
import ua.rp.chat.client.microvoxel.MicrovoxelActionPayload;
import ua.rp.chat.client.microvoxel.MicrovoxelClientRenderer;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.client.microvoxel.MicrovoxelInteractionController;
import ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload;
import ua.rp.chat.client.heavyhammer.HeavyHammerActionPayload;
import ua.rp.chat.client.heavyhammer.HeavyHammerClientState;
import ua.rp.chat.client.heavyhammer.HeavyHammerCarryRenderLayer;
import ua.rp.chat.client.heavyhammer.HeavyHammerInteractionController;
import ua.rp.chat.client.heavyhammer.HeavyHammerSyncPayload;
import ua.rp.chat.client.heavyhammer.HammerHolsterClientState;
import ua.rp.chat.client.debug.HammerRenderQaController;
import ua.rp.chat.client.pickup.PickupClientState;
import ua.rp.chat.client.pickup.ItemPickupPayload;
import ua.rp.chat.client.pickup.GroundedLootRenderer;
import ua.rp.chat.client.rpfeed.RpChatFeedClientState;
import ua.rp.chat.client.rpfeed.RpChatFeedPayload;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class EclipseClientMod implements ClientModInitializer {

    public static final String MOD_ID = "eclipseclient";
    public static final String DIAGNOSTIC_BUILD = "heavy-hammer-holster-equipment-20260717-1";
    public static final Logger LOGGER = LogManager.getLogger("EclipseAuth");
    private static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.withDefaultNamespace("tools_and_utilities")
    );
    private static final AtomicBoolean SESSION_CHECK_IN_FLIGHT = new AtomicBoolean(false);
    private static KeyMapping bodyStatusKey;
    private static int sessionPollTicks = 0;
    private static String lastOpenedUrl = "";
    public static long lastBodyScreenCloseTime = 0;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Eclipse RolePlay Client initialized! Diagnostic build=" + DIAGNOSTIC_BUILD
                + ", java=" + System.getProperty("java.version")
                + ", os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        EclipseHudOverlay.register();
        PickupClientState.register();
        MicrovoxelClientRenderer.register();
        MicrovoxelInteractionController.register();
        HammerRenderQaController.register();
        HeavyHammerCarryRenderLayer.register();
        // Предмет доступен в творческом инвентаре, но создаётся только после завершения загрузки реестров.
        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES_TAB).register(output ->
                output.prepend(HammerHolsterClientState.createStack()));
        bodyStatusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.body_status",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.GAMEPLAY
        ));
        LOGGER.info("[BODY] Key mapping registered: translation=key.eclipseclient.body_status, default=B");

        // Register custom payload codec
        PayloadTypeRegistry.clientboundPlay().register(AuthPayload.TYPE, AuthPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AcquaintancePayload.TYPE, AcquaintancePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CombatIntentPayload.TYPE, CombatIntentPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AcquaintanceActionPayload.TYPE, AcquaintanceActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MicrovoxelSyncPayload.TYPE, MicrovoxelSyncPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MicrovoxelActionPayload.TYPE, MicrovoxelActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HeavyHammerSyncPayload.TYPE, HeavyHammerSyncPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HeavyHammerActionPayload.TYPE, HeavyHammerActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ItemPickupPayload.TYPE, ItemPickupPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RpChatFeedPayload.TYPE, RpChatFeedPayload.CODEC);

        // Register packet receiver
        ClientPlayNetworking.registerGlobalReceiver(AuthPayload.TYPE, (payload, context) -> {
            String authUrl = payload.authUrl();
            LOGGER.info("[AUTH] Received server trigger: url=" + McefDiagnostics.safeUrl(authUrl));
            
            context.client().execute(() -> {
                openAuthScreen(context.client(), authUrl);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AcquaintancePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> AcquaintanceClientState.handle(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(MicrovoxelSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> MicrovoxelClientState.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(HeavyHammerSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> HeavyHammerClientState.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(RpChatFeedPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RpChatFeedClientState.accept(payload)));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VitalsClientState.clientTick(client);
            SmartCameraManager.getInstance().clientTick(client);
            AcquaintanceClientState.clientTick(client);
            MicrovoxelClientState.clientTick(client);
            MicrovoxelInteractionController.tick(client);
            HeavyHammerInteractionController.tick(client);
            HeavyHammerClientState.clientTick(client);
            HammerHolsterClientState.clientTick(client);
            HammerRenderQaController.clientTick(client);
            PickupClientState.clientTick(client);
            GroundedLootRenderer.clientTick(client);
            handleBodyStatusKey(client);
            pollAuthSession(client);
        });
    }

    private void handleBodyStatusKey(Minecraft client) {
        if (client == null || bodyStatusKey == null) {
            return;
        }
        while (bodyStatusKey.consumeClick()) {
            String currentScreen = client.screen == null ? "none" : client.screen.getClass().getSimpleName();
            String gameMode = client.gameMode == null ? "unknown" : String.valueOf(client.gameMode.getPlayerMode());
            LOGGER.info("[BODY] B key consumed: player=" + (client.player != null)
                    + ", level=" + (client.level != null)
                    + ", currentScreen=" + currentScreen
                    + ", gameMode=" + gameMode);
            if (client.player == null || client.level == null || client.screen != null) {
                LOGGER.info("[BODY] B request ignored because the client is not in a screen-free playable state.");
                continue;
            }
            if (System.currentTimeMillis() - lastBodyScreenCloseTime < 500) {
                LOGGER.info("[BODY] B request ignored due to cooldown.");
                continue;
            }
            String bodyUrl = VitalsClientState.bodyStatusUrl();
            LOGGER.info("[BODY] Creating body status screen: url=" + McefDiagnostics.safeUrl(bodyUrl));
            client.setScreen(new BodyStatusScreen(bodyUrl));
            LOGGER.info("[BODY] setScreen returned: currentScreen="
                    + (client.screen == null ? "none" : client.screen.getClass().getSimpleName()));
        }
    }

    private void pollAuthSession(Minecraft client) {
        if (client.player == null || client.level == null) {
            sessionPollTicks = 0;
            lastOpenedUrl = "";
            return;
        }

        if (SESSION_CHECK_IN_FLIGHT.get()) {
            return;
        }

        sessionPollTicks++;
        if (sessionPollTicks < 20) {
            return;
        }
        sessionPollTicks = 0;

        String username = client.getUser().getName();
        String sessionUrl = EclipseApiClient.resolve("/api/client-session?username=")
                + URLEncoder.encode(username, StandardCharsets.UTF_8);
        SESSION_CHECK_IN_FLIGHT.set(true);

        CompletableFuture.supplyAsync(() -> fetchAuthUrl(sessionUrl))
                .whenComplete((authUrl, throwable) -> {
                    SESSION_CHECK_IN_FLIGHT.set(false);
                    if (throwable != null) {
                        LOGGER.debug("Eclipse auth session poll failed.", throwable);
                        return;
                    }
                    if (authUrl == null || authUrl.isBlank() || authUrl.equals(lastOpenedUrl)) {
                        return;
                    }
                    LOGGER.info("[AUTH] Session poll found a new auth flow: url=" + McefDiagnostics.safeUrl(authUrl));
                    client.execute(() -> openAuthScreen(client, authUrl));
                });
    }

    private static void openAuthScreen(Minecraft client, String authUrl) {
        if (!isValidAuthUrl(authUrl)) {
            LOGGER.warn("[AUTH] Ignored invalid Eclipse auth URL: " + McefDiagnostics.safeUrl(authUrl));
            return;
        }
        if (client.screen instanceof AuthScreen current && current.usesAuthUrl(authUrl)) {
            LOGGER.info("[AUTH] Existing AuthScreen already owns this URL; duplicate trigger ignored.");
            return;
        }
        LOGGER.info("[AUTH] Replacing screen "
                + (client.screen == null ? "none" : client.screen.getClass().getSimpleName())
                + " with a new AuthScreen: url=" + McefDiagnostics.safeUrl(authUrl));
        EclipseApiClient.rememberFromUrl(authUrl);
        lastOpenedUrl = authUrl;
        client.setScreen(new AuthScreen(authUrl));
        LOGGER.info("[AUTH] setScreen returned: currentScreen="
                + (client.screen == null ? "none" : client.screen.getClass().getSimpleName()));
    }

    private static String fetchAuthUrl(String sessionUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(sessionUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(500);
            connection.setReadTimeout(800);

            int code = connection.getResponseCode();
            if (code == 204) {
                return null;
            }
            if (code != 200) {
                LOGGER.debug("Eclipse auth session endpoint returned HTTP " + code);
                return null;
            }

            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            return response.has("authUrl") && !response.get("authUrl").isJsonNull()
                    ? response.get("authUrl").getAsString()
                    : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Unable to poll Eclipse auth session.", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isValidAuthUrl(String authUrl) {
        if (authUrl == null || authUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(authUrl);
            String query = uri.getRawQuery();
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && query != null
                    && ("&" + query + "&").contains("&token=")
                    && !query.matches(".*(?:^|&)token=(?:&|$).*");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
