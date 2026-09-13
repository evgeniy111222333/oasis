package ua.rp.chat.client.vitals;

import net.minecraft.client.Minecraft;
import ua.rp.chat.RespirationModel;
import ua.rp.chat.client.EclipseApiClient;
import ua.rp.chat.client.EclipseClientMod;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VitalsClientState {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static int pollTicks;
    private static volatile float stamina = 100.0f;
    private static volatile float breathDebt = 0.0f;
    private static volatile float fatigue = 0.0f;
    private static volatile float blood = 100.0f;
    private static volatile float pain = 0.0f;
    private static volatile float bleeding = 0.0f;
    private static volatile float targetStamina = 100.0f;
    private static volatile float targetBreathDebt = 0.0f;
    private static volatile float targetFatigue = 0.0f;
    private static volatile float targetBlood = 100.0f;
    private static volatile float targetPain = 0.0f;
    private static volatile float targetBleeding = 0.0f;
    private static volatile boolean unconscious = false;
    private static volatile String band = "steady";

    private VitalsClientState() {
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            stamina = 100.0f;
            breathDebt = 0.0f;
            fatigue = 0.0f;
            blood = 100.0f;
            pain = 0.0f;
            bleeding = 0.0f;
            targetStamina = 100.0f;
            targetBreathDebt = 0.0f;
            targetFatigue = 0.0f;
            targetBlood = 100.0f;
            targetPain = 0.0f;
            targetBleeding = 0.0f;
            unconscious = false;
            band = "steady";
            pollTicks = 0;
            return;
        }
        smoothVitals();
        if (IN_FLIGHT.get()) {
            return;
        }
        pollTicks++;
        if (pollTicks < 8) {
            return;
        }
        pollTicks = 0;
        String username = client.getUser().getName();
        String url = EclipseApiClient.resolve("/api/vitals?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&ts=" + System.currentTimeMillis());
        IN_FLIGHT.set(true);
        CompletableFuture.supplyAsync(() -> get(url))
                .whenComplete((body, error) -> {
                    IN_FLIGHT.set(false);
                    if (error != null || body == null || body.isBlank()) {
                        return;
                    }
                    targetStamina = extractFloat(body, "stamina", targetStamina);
                    targetBreathDebt = extractFloat(body, "breathDebt", targetBreathDebt);
                    targetFatigue = extractFloat(body, "fatigue", targetFatigue);
                    targetBlood = extractFloat(body, "blood", targetBlood);
                    targetPain = extractFloat(body, "pain", targetPain);
                    targetBleeding = extractFloat(body, "bleeding", targetBleeding);
                    unconscious = extractBoolean(body, "unconscious", unconscious);
                    String nextBand = extractString(body, "band");
                    if (nextBand != null && !nextBand.isBlank()) {
                        band = nextBand;
                    }
                });
    }

    public static String bodyStatusUrl() {
        Minecraft client = Minecraft.getInstance();
        String username = client != null && client.getUser() != null ? client.getUser().getName() : "";
        return EclipseApiClient.resolve("/body?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8));
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

    public static float getBlood01() {
        return clamp(blood / 100.0f, 0.0f, 1.0f);
    }

    public static float getPain() {
        return pain;
    }

    public static float getBleeding() {
        return bleeding;
    }

    public static boolean isUnconscious() {
        return unconscious;
    }

    public static String getBand() {
        return band;
    }

    private static void smoothVitals() {
        stamina = smooth(stamina, targetStamina, targetStamina < stamina ? 0.55f : 1.40f);
        breathDebt = smooth(breathDebt, targetBreathDebt, targetBreathDebt > breathDebt ? 0.50f : 1.80f);
        fatigue = smooth(fatigue, targetFatigue, targetFatigue > fatigue ? 0.80f : 2.80f);
        blood = smooth(blood, targetBlood, targetBlood < blood ? 0.45f : 1.50f);
        pain = smooth(pain, targetPain, targetPain > pain ? 0.35f : 0.90f);
        bleeding = smooth(bleeding, targetBleeding, targetBleeding > bleeding ? 0.35f : 0.85f);
    }

    private static float smooth(float current, float target, float timeConstant) {
        return (float) RespirationModel.smoothSignal(current, target, 0.05, timeConstant);
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
            EclipseClientMod.LOGGER.debug("Unable to poll Eclipse vitals.", e);
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

    private static boolean extractBoolean(String body, String propertyName, boolean fallback) {
        String marker = "\"" + propertyName + "\":";
        int start = body.indexOf(marker);
        if (start < 0) {
            return fallback;
        }
        int i = start + marker.length();
        if (body.startsWith("true", i)) {
            return true;
        }
        if (body.startsWith("false", i)) {
            return false;
        }
        return fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
