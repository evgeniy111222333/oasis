package ua.rp.chat.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AcquaintanceClientState {
    private static final Map<Integer, LabelInfo> LABELS = new HashMap<>();
    private static Toast toast;
    private static PendingRequest pendingRequest;
    private static HandshakeFx handshakeFx;
    private static boolean gDown;
    private static boolean yDown;
    private static boolean nDown;

    private AcquaintanceClientState() {
    }

    public static void handle(AcquaintancePayload payload) {
        try {
            JsonObject json = JsonParser.parseString(payload.json()).getAsJsonObject();
            String type = json.get("type").getAsString();
            switch (type) {
                case "state" -> updateState(json.getAsJsonArray("players"));
                case "toast" -> toast(json.get("text").getAsString(), json.has("tone") ? json.get("tone").getAsString() : "sand");
                case "request" -> pendingRequest = new PendingRequest(
                        UUID.fromString(json.get("from").getAsString()),
                        json.get("text").getAsString(),
                        json.get("hint").getAsString(),
                        360
                );
                case "introduce" -> openInput(
                        UUID.fromString(json.get("target").getAsString()),
                        4,
                        json.get("title").getAsString(),
                        json.get("defaultName").getAsString()
                );
                case "handshake" -> handshakeFx = new HandshakeFx(0);
                default -> {
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            LABELS.clear();
            pendingRequest = null;
            return;
        }
        if (toast != null && --toast.ticks <= 0) {
            toast = null;
        }
        if (pendingRequest != null && --pendingRequest.ticks <= 0) {
            pendingRequest = null;
        }
        if (handshakeFx != null && ++handshakeFx.ticks > 92) {
            handshakeFx = null;
        }

        long handle = client.getWindow().handle();
        boolean g = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
        boolean y = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_Y) == GLFW.GLFW_PRESS;
        boolean n = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_N) == GLFW.GLFW_PRESS;
        if (g && !gDown && client.screen == null) {
            Player target = targetedPlayer(client);
            if (target != null) {
                client.setScreen(new AcquaintanceRadialScreen(target.getUUID(), labelFor(target.getId())));
            } else {
                toast("Посмотрите на персонажа, чтобы взаимодействовать.", "muted");
            }
        }
        if (pendingRequest != null && y && !yDown) {
            send(2, pendingRequest.from, "");
            pendingRequest = null;
        }
        if (pendingRequest != null && n && !nDown) {
            send(3, pendingRequest.from, "");
            pendingRequest = null;
        }
        gDown = g;
        yDown = y;
        nDown = n;
    }

    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) {
            return;
        }
        Player target = targetedPlayer(client);
        if (target != null) {
            LabelInfo label = LABELS.get(target.getId());
            if (label != null) {
                int color = label.known ? 0xFFE3C099 : label.note == null || label.note.isBlank() ? 0xFF8F8F8F : 0xFF7FD0CC;
                int x = width / 2;
                int y = height / 2 - 48;
                int textWidth = client.font.width(label.label);
                int boxWidth = Math.max(128, textWidth + 22);
                graphics.fill(x - boxWidth / 2, y - 7, x + boxWidth / 2, y + 15, 0xA812100E);
                graphics.fill(x - boxWidth / 2, y - 7, x + boxWidth / 2, y - 6, label.known ? 0xAAE3C099 : 0x887FD0CC);
                graphics.centeredText(client.font, label.label, x, y, color);
                graphics.centeredText(client.font, "G - взаимодействие", x, y + 17, 0x99D8D1C8);
            }
        }
        if (pendingRequest != null) {
            int boxW = Math.min(300, width - 36);
            int x = width - boxW - 18;
            int y = 46;
            graphics.fill(x, y, x + boxW, y + 66, 0xE017130F);
            graphics.fill(x, y, x + 3, y + 66, 0xFFE3C099);
            graphics.textWithWordWrap(client.font, Component.literal(pendingRequest.text), x + 12, y + 12, boxW - 24, 0xFFEDE1D0);
            graphics.text(client.font, pendingRequest.hint, x + 12, y + 48, 0xFFA5C3C4);
        }
        if (toast != null) {
            int a = Math.min(220, toast.ticks * 9);
            int color = "muted".equals(toast.tone) ? 0xFFB0A8A0 : 0xFFE3C099;
            int boxW = Math.min(340, width - 48);
            int x = width / 2 - boxW / 2;
            int y = height - 86;
            graphics.fill(x, y, x + boxW, y + 30, (a << 24) | 0x17130F);
            graphics.centeredText(client.font, toast.text, width / 2, y + 10, color);
        }
        if (handshakeFx != null) {
            renderHandshake(graphics, width, height, handshakeFx.ticks);
        }
    }

    public static void openNote(UUID target, String label) {
        Minecraft.getInstance().setScreen(new AcquaintanceInputScreen(target, 5, "Записать примету", ""));
    }

    public static void greet(UUID target) {
        send(1, target, "");
    }

    static void send(int action, UUID target, String text) {
        try {
            ClientPlayNetworking.send(new AcquaintanceActionPayload(action, target, text == null ? "" : text));
        } catch (RuntimeException ignored) {
        }
    }

    private static void openInput(UUID target, int action, String title, String defaultValue) {
        Minecraft.getInstance().setScreen(new AcquaintanceInputScreen(target, action, title, defaultValue));
    }

    private static void updateState(JsonArray players) {
        LABELS.clear();
        if (players == null) {
            return;
        }
        for (int i = 0; i < players.size(); i++) {
            JsonObject p = players.get(i).getAsJsonObject();
            LABELS.put(p.get("entityId").getAsInt(), new LabelInfo(
                    p.get("label").getAsString(),
                    p.get("known").getAsBoolean(),
                    p.has("note") ? p.get("note").getAsString() : "",
                    p.has("masked") && p.get("masked").getAsBoolean()
            ));
        }
    }

    private static String labelFor(int entityId) {
        LabelInfo info = LABELS.get(entityId);
        return info == null ? "Незнакомец" : info.label;
    }

    private static Player targetedPlayer(Minecraft client) {
        if (client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player player && player != client.player) {
            return player;
        }
        return null;
    }

    private static void toast(String text, String tone) {
        toast = new Toast(text, tone, 100);
    }

    private static void renderHandshake(GuiGraphicsExtractor graphics, int width, int height, int ticks) {
        float t = Math.min(1.0f, ticks / 92.0f);
        int cx = width / 2;
        int y = height - 132;
        int alpha = ticks < 12 ? ticks * 18 : ticks > 76 ? Math.max(0, (92 - ticks) * 14) : 220;
        int left = (int) (cx - 142 + Math.min(1.0f, t * 2.4f) * 78);
        int right = (int) (cx + 142 - Math.min(1.0f, Math.max(0.0f, (t - 0.12f) * 2.6f)) * 78);
        int shake = ticks > 35 && ticks < 67 ? ((ticks / 4) % 2 == 0 ? 3 : -3) : 0;
        int hand = (alpha << 24) | 0xE3C099;
        int sleeve = (alpha << 24) | 0x4A3326;

        graphics.fill(left - 54, y + 2, left + 8, y + 18, sleeve);
        graphics.fill(left + 6 + shake, y, left + 78 + shake, y + 20, hand);
        graphics.fill(right - 8 - shake, y + 24, right + 54, y + 40, sleeve);
        graphics.fill(right - 78 - shake, y + 22, right - 6 - shake, y + 42, hand);
        if (ticks > 32 && ticks < 70) {
            graphics.fill(cx - 34, y + 15, cx + 34, y + 29, (alpha << 24) | 0xA5C3C4);
            graphics.centeredText(Minecraft.getInstance().font, "рукопожатие", cx, y - 18, (alpha << 24) | 0xEDE1D0);
        }
    }

    private record LabelInfo(String label, boolean known, String note, boolean masked) {
    }

    private static final class Toast {
        private final String text;
        private final String tone;
        private int ticks;

        private Toast(String text, String tone, int ticks) {
            this.text = text;
            this.tone = tone;
            this.ticks = ticks;
        }
    }

    private static final class PendingRequest {
        private final UUID from;
        private final String text;
        private final String hint;
        private int ticks;

        private PendingRequest(UUID from, String text, String hint, int ticks) {
            this.from = from;
            this.text = text;
            this.hint = hint;
            this.ticks = ticks;
        }
    }

    private static final class HandshakeFx {
        private int ticks;

        private HandshakeFx(int ticks) {
            this.ticks = ticks;
        }
    }
}
