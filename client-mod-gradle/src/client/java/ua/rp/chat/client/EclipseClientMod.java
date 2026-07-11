package ua.rp.chat.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ua.rp.chat.client.camera.EclipseHudOverlay;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.vitals.VitalsClientState;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class EclipseClientMod implements ClientModInitializer {

    public static final String MOD_ID = "eclipseclient";
    public static final Logger LOGGER = LogManager.getLogger("EclipseAuth");
    private static final AtomicBoolean SESSION_CHECK_IN_FLIGHT = new AtomicBoolean(false);
    private static boolean bodyStatusKeyDown = false;
    private static int sessionPollTicks = 0;
    private static String lastOpenedUrl = "";

    @Override
    public void onInitializeClient() {
        LOGGER.info("Eclipse RolePlay Client initialized! Waiting for server packages...");
        EclipseHudOverlay.register();

        // Register custom payload codec
        PayloadTypeRegistry.clientboundPlay().register(AuthPayload.TYPE, AuthPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AcquaintancePayload.TYPE, AcquaintancePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CombatIntentPayload.TYPE, CombatIntentPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AcquaintanceActionPayload.TYPE, AcquaintanceActionPayload.CODEC);

        // Register packet receiver
        ClientPlayNetworking.registerGlobalReceiver(AuthPayload.TYPE, (payload, context) -> {
            String authUrl = payload.authUrl();
            LOGGER.info("Received auth trigger from server. URL: " + authUrl);
            
            context.client().execute(() -> {
                openAuthScreen(context.client(), authUrl);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AcquaintancePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> AcquaintanceClientState.handle(payload));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SmartCameraManager.getInstance().clientTick(client);
            VitalsClientState.clientTick(client);
            AcquaintanceClientState.clientTick(client);
            handleBodyStatusKey(client);
            pollAuthSession(client);
        });
    }

    private void handleBodyStatusKey(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        boolean down = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS;
        if (down && !bodyStatusKeyDown && client.player != null && client.level != null && client.screen == null) {
            client.setScreen(new BodyStatusScreen(VitalsClientState.bodyStatusUrl()));
        }
        bodyStatusKeyDown = down;
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
                    client.execute(() -> openAuthScreen(client, authUrl));
                });
    }

    private static void openAuthScreen(Minecraft client, String authUrl) {
        if (!isValidAuthUrl(authUrl)) {
            LOGGER.warn("Ignored invalid Eclipse auth URL: " + authUrl);
            return;
        }
        if (client.screen instanceof AuthScreen current && current.usesAuthUrl(authUrl)) {
            return;
        }
        EclipseApiClient.rememberFromUrl(authUrl);
        lastOpenedUrl = authUrl;
        client.setScreen(new AuthScreen(authUrl));
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
