package ua.rp.chat.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ua.rp.chat.client.camera.OasisHudOverlay;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.vitals.VitalsClientState;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class OasisAuthMod implements ClientModInitializer {

    public static final String MOD_ID = "oasisauth";
    public static final Logger LOGGER = LogManager.getLogger("OasisAuth");
    private static final AtomicBoolean SESSION_CHECK_IN_FLIGHT = new AtomicBoolean(false);
    private static boolean bodyStatusKeyDown = false;
    private static int sessionPollTicks = 0;
    private static String lastOpenedUrl = "";

    @Override
    public void onInitializeClient() {
        LOGGER.info("Oasis Auth Mod initialized! Waiting for server packages...");
        OasisHudOverlay.register();

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

        if (client.screen instanceof AuthScreen || SESSION_CHECK_IN_FLIGHT.get()) {
            return;
        }

        sessionPollTicks++;
        if (sessionPollTicks < 5) {
            return;
        }
        sessionPollTicks = 0;

        String username = client.getUser().getName();
        String sessionUrl = OasisApiClient.resolve("/api/client-session?username=")
                + URLEncoder.encode(username, StandardCharsets.UTF_8);
        SESSION_CHECK_IN_FLIGHT.set(true);

        CompletableFuture.supplyAsync(() -> fetchAuthUrl(sessionUrl))
                .whenComplete((authUrl, throwable) -> {
                    SESSION_CHECK_IN_FLIGHT.set(false);
                    if (throwable != null) {
                        LOGGER.debug("Oasis auth session poll failed.", throwable);
                        return;
                    }
                    if (authUrl == null || authUrl.isBlank() || authUrl.equals(lastOpenedUrl)) {
                        return;
                    }
                    client.execute(() -> openAuthScreen(client, authUrl));
                });
    }

    private static void openAuthScreen(Minecraft client, String authUrl) {
        if (client.screen instanceof AuthScreen || authUrl == null || authUrl.isBlank()) {
            return;
        }
        OasisApiClient.rememberFromUrl(authUrl);
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
                LOGGER.debug("Oasis auth session endpoint returned HTTP " + code);
                return null;
            }

            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return extractJsonString(body, "authUrl");
        } catch (IOException e) {
            LOGGER.debug("Unable to poll Oasis auth session.", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String extractJsonString(String body, String propertyName) {
        String marker = "\"" + propertyName + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return null;
        }

        StringBuilder value = new StringBuilder();
        for (int i = start + marker.length(); i < body.length(); i++) {
            char current = body.charAt(i);
            if (current == '"') {
                return value.toString();
            }
            if (current == '\\' && i + 1 < body.length()) {
                char escaped = body.charAt(++i);
                value.append(escaped);
            } else {
                value.append(current);
            }
        }
        return null;
    }
}
