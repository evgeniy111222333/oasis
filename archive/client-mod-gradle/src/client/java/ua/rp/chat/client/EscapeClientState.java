package ua.rp.chat.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public final class EscapeClientState {
    private static Capabilities capabilities = Capabilities.FREE;
    private static EscapeProgress progress;
    private static String transientMessage = "";
    private static int transientTicks;
    private static boolean impulseDown;
    private static int impulseTaps;
    private static int impulseWindow;

    private EscapeClientState() {
    }

    public static void updateCapabilities(JsonObject json) {
        boolean bound = json.has("bound") && json.get("bound").getAsBoolean();
        if (!bound) {
            capabilities = Capabilities.FREE;
            progress = null;
            return;
        }
        capabilities = new Capabilities(
                true,
                text(json, "restraintMaterial", "путы"),
                number(json, "restraintDurability", 1.0),
                number(json, "restraintMax", 1.0),
                bool(json, "canStruggle"),
                bool(json, "canBlade"),
                bool(json, "canEnvironment"),
                number(json, "escapeStamina", 0.0)
        );
    }

    public static void handle(JsonObject json) {
        boolean active = bool(json, "active");
        String message = text(json, "message", "");
        if (!active) {
            progress = null;
            transientMessage = message;
            transientTicks = message.isBlank() ? 0 : 90;
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof EscapeStruggleScreen) {
                client.setScreen(null);
            }
            return;
        }
        progress = new EscapeProgress(
                text(json, "mode", "STRUGGLE"),
                clamp(number(json, "progress", 0.0)),
                message,
                longNumber(json, "startedAt"),
                longNumber(json, "completeAt"),
                longNumber(json, "cycleStartedAt"),
                Math.max(1L, longNumber(json, "cycleDurationMs")),
                clamp(number(json, "windowCenter", 0.5)),
                clamp(number(json, "windowWidth", 0.15))
        );
        if ("STRUGGLE".equals(progress.mode)) {
            Minecraft client = Minecraft.getInstance();
            if (!(client.screen instanceof EscapeStruggleScreen)) {
                client.setScreen(new EscapeStruggleScreen());
            }
        }
    }

    public static void reset() {
        capabilities = Capabilities.FREE;
        progress = null;
        transientMessage = "";
        transientTicks = 0;
        impulseDown = false;
        impulseTaps = 0;
        impulseWindow = 0;
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.player == null || !capabilities.bound || client.screen != null || progress != null) {
            impulseDown = false;
            if (impulseWindow > 0) impulseWindow--;
            return;
        }
        if (impulseWindow > 0 && --impulseWindow == 0) impulseTaps = 0;
        long window = client.getWindow().handle();
        boolean impulse = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (impulse && !impulseDown) {
            impulseTaps++;
            impulseWindow = 28;
            if (impulseTaps >= 3 && capabilities.canStruggle) {
                impulseTaps = 0;
                impulseWindow = 0;
                action(70);
            }
        }
        impulseDown = impulse;
    }

    public static boolean isBound() {
        return capabilities.bound;
    }

    public static Capabilities capabilities() {
        return capabilities;
    }

    public static EscapeProgress progress() {
        return progress;
    }

    public static void action(int action) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            AcquaintanceClientState.send(action, client.player.getUUID(), "");
        }
    }

    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.font == null || client.screen != null) {
            return;
        }
        if (transientTicks > 0) transientTicks--;

        if (progress != null && !"STRUGGLE".equals(progress.mode)) {
            int panelW = Math.min(344, width - 42);
            int x = width / 2 - panelW / 2;
            int y = height - 116;
            graphics.fill(x, y, x + panelW, y + 52, 0xD815110D);
            graphics.fill(x, y, x + panelW, y + 2, 0xFFE3C099);
            graphics.fill(x + 12, y + 29, x + panelW - 12, y + 35, 0xFF30271F);
            int fill = (int) ((panelW - 24) * progress.progress);
            graphics.fill(x + 12, y + 29, x + 12 + fill, y + 35, modeColor(progress.mode));
            graphics.centeredText(client.font, modeTitle(progress.mode), width / 2, y + 8, 0xFFFFE8C5);
            graphics.centeredText(client.font, fit(client, progress.message, panelW - 28), width / 2, y + 39, 0xFFA5C3C4);
            return;
        }
        if (transientTicks > 0 && !transientMessage.isBlank()) {
            int boxW = Math.min(350, width - 48);
            int y = height - 94;
            graphics.fill(width / 2 - boxW / 2, y, width / 2 + boxW / 2, y + 28, 0xD817130F);
            graphics.centeredText(client.font, fit(client, transientMessage, boxW - 20), width / 2, y + 9, 0xFFE3C099);
            return;
        }
        if (capabilities.bound) {
            int y = height - 70;
            String hint = "[G] Попытаться освободиться";
            int w = client.font.width(hint) + 24;
            graphics.fill(width / 2 - w / 2, y - 5, width / 2 + w / 2, y + 17, 0xB014100D);
            graphics.fill(width / 2 - w / 2, y - 5, width / 2 + w / 2, y - 3, 0xAAE3C099);
            graphics.centeredText(client.font, hint, width / 2, y + 2, 0xFFEAD1A8);
        }
    }

    private static int modeColor(String mode) {
        return switch (mode) {
            case "FIRE" -> 0xFFE07B42;
            case "STONE" -> 0xFFA5B0A6;
            case "HELP" -> 0xFF7FC5BD;
            default -> 0xFFD5B16F;
        };
    }

    private static String modeTitle(String mode) {
        return switch (mode) {
            case "BLADE" -> "ПЕРЕРЕЗАНИЕ ПУТ";
            case "STONE" -> "ТРЕНИЕ О КАМЕНЬ";
            case "FIRE" -> "ОГОНЬ У ЗАПЯСТИЙ";
            case "HELP" -> "РАЗВЯЗЫВАНИЕ УЗЛА";
            default -> "ПОПЫТКА ОСВОБОДИТЬСЯ";
        };
    }

    private static String fit(Minecraft client, String value, int maxWidth) {
        String result = value == null ? "" : value;
        while (!result.isEmpty() && client.font.width(result) > maxWidth) result = result.substring(0, result.length() - 1);
        return result.equals(value) ? result : result + "...";
    }

    private static boolean bool(JsonObject json, String key) {
        return json.has(key) && json.get(key).getAsBoolean();
    }

    private static String text(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    private static long longNumber(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsLong() : 0L;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Capabilities(boolean bound, String material, double durability, double maxDurability,
                               boolean canStruggle, boolean canBlade, boolean canEnvironment, double stamina) {
        private static final Capabilities FREE = new Capabilities(false, "", 0, 0, false, false, false, 0);
    }

    public record EscapeProgress(String mode, double progress, String message, long startedAt, long completeAt,
                                 long cycleStartedAt, long cycleDurationMs, double windowCenter, double windowWidth) {
    }
}
