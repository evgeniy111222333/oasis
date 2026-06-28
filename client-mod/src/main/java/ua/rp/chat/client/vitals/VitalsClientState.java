package ua.rp.chat.client.vitals;

import net.minecraft.client.Minecraft;
import ua.rp.chat.client.OasisAuthMod;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VitalsClientState {
    private static final String BASE_URL = "http://localhost:25580";
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static int pollTicks;
    private static volatile float stamina = 100.0f;
    private static volatile float breathDebt = 0.0f;
    private static volatile float fatigue = 0.0f;
    private static volatile String band = "steady";

    private VitalsClientState() {
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            stamina = 100.0f;
            breathDebt = 0.0f;
            fatigue = 0.0f;
            band = "steady";
            pollTicks = 0;
            return;
        }
        if (IN_FLIGHT.get()) {
            return;
        }
        pollTicks++;
        if (pollTicks < 8) {
            return;
        }
        pollTicks = 0;
        String username = client.getUser().getName();
        String url = BASE_URL + "/api/vitals?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&ts=" + System.currentTimeMillis();
        IN_FLIGHT.set(true);
        CompletableFuture.supplyAsync(() -> get(url))
                .whenComplete((body, error) -> {
                    IN_FLIGHT.set(false);
                    if (error != null || body == null || body.isBlank()) {
                        return;
                    }
                    stamina = extractFloat(body, "stamina", stamina);
                    breathDebt = extractFloat(body, "breathDebt", breathDebt);
                    fatigue = extractFloat(body, "fatigue", fatigue);
                    String nextBand = extractString(body, "band");
                    if (nextBand != null && !nextBand.isBlank()) {
                        band = nextBand;
                    }
                });
    }

    public static String bodyStatusUrl() {
        Minecraft client = Minecraft.getInstance();
        String username = client != null && client.getUser() != null ? client.getUser().getName() : "";
        return BASE_URL + "/body?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&ts=" + System.currentTimeMillis();
    }

    public static float getStamina01() {
        return clamp(stamina / 100.0f, 0.0f, 1.0f);
    }

    public static float getStamina() {
        return stamina;
    }

    public static float getBreathDebt() {
        return breathDebt;
    }

    public static float getFatigue() {
        return fatigue;
    }

    public static String getBand() {
        return band;
    }

    private static String get(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(450);
            connection.setReadTimeout(700);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            OasisAuthMod.LOGGER.debug("Unable to poll Oasis vitals.", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static float extractFloat(String body, String propertyName, float fallback) {
        String marker = "\"" + propertyName + "\":";
        int start = body.indexOf(marker);
        if (start < 0) {
            return fallback;
        }
        int i = start + marker.length();
        int end = i;
        while (end < body.length()) {
            char c = body.charAt(end);
            if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
                end++;
            } else {
                break;
            }
        }
        try {
            return Float.parseFloat(body.substring(i, end));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String extractString(String body, String propertyName) {
        String marker = "\"" + propertyName + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int end = body.indexOf('"', valueStart);
        return end > valueStart ? body.substring(valueStart, end) : null;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
