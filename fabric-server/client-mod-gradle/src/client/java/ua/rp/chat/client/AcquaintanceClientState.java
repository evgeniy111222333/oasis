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
import ua.rp.chat.client.tab.EclipseTabRenderer;
import ua.rp.chat.client.tab.TabRoster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AcquaintanceClientState {
    private static final Map<Integer, LabelInfo> LABELS = new HashMap<>();
    private static final Map<UUID, PhysicalPose> PHYSICAL_POSES = new HashMap<>();
    private static Toast toast;
    private static PendingRequest pendingRequest;
    private static HandshakeFx handshakeFx;
    private static boolean gDown;
    private static boolean yDown;
    private static boolean nDown;
    private static final TabRoster TAB_ROSTER = new TabRoster();
    private static long nextFallbackRefreshNanos;

    private AcquaintanceClientState() {
    }

    public static void handle(AcquaintancePayload payload) {
        try {
            JsonObject json = JsonParser.parseString(payload.json()).getAsJsonObject();
            String type = json.get("type").getAsString();
            switch (type) {
                case "state" -> updateState(json.getAsJsonArray("players"), json.has("tab") ? json.getAsJsonArray("tab") : null);
                case "toast" -> toast(json.get("text").getAsString(), json.has("tone") ? json.get("tone").getAsString() : "sand");
                case "request" -> pendingRequest = new PendingRequest(
                        UUID.fromString(json.get("from").getAsString()),
                        json.get("text").getAsString(),
                        json.get("hint").getAsString(),
                        2,
                        3,
                        360
                );
                case "control_request" -> pendingRequest = new PendingRequest(
                        UUID.fromString(json.get("from").getAsString()),
                        json.get("text").getAsString(),
                        json.get("hint").getAsString(),
                        json.has("acceptAction") ? json.get("acceptAction").getAsInt() : 40,
                        json.has("declineAction") ? json.get("declineAction").getAsInt() : 41,
                        180
                );
                case "search_results" -> openSearchResults(json);
                case "physical_action" -> updatePhysicalAction(json);
                case "escape_state" -> EscapeClientState.handle(json);
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
            reset();
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
        PHYSICAL_POSES.entrySet().removeIf(entry -> entry.getValue().isExpired());
        EscapeClientState.clientTick(client);

        long handle = client.getWindow().handle();
        EclipseTabRenderer.tick(client);
        boolean g = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
        boolean y = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_Y) == GLFW.GLFW_PRESS;
        boolean n = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_N) == GLFW.GLFW_PRESS;
        if (g && !gDown && client.screen == null) {
            Player target = targetedPlayer(client);
            if (target != null) {
                client.setScreen(new AcquaintanceRadialScreen(target.getUUID(), labelFor(target)));
            } else if (EscapeClientState.isBound()) {
                client.setScreen(new EscapeRadialScreen());
            } else {
                toast("Посмотрите на персонажа, чтобы взаимодействовать.", "muted");
            }
        }
        if (pendingRequest != null && y && !yDown) {
            send(pendingRequest.acceptAction, pendingRequest.from, "");
            pendingRequest = null;
        }
        if (pendingRequest != null && n && !nDown) {
            send(pendingRequest.declineAction, pendingRequest.from, "");
            pendingRequest = null;
        }
        gDown = g;
        yDown = y;
        nDown = n;
    }

    public static void reset() {
        LABELS.clear();
        TAB_ROSTER.clear();
        PHYSICAL_POSES.clear();
        pendingRequest = null;
        toast = null;
        handshakeFx = null;
        gDown = yDown = nDown = false;
        nextFallbackRefreshNanos = 0;
        EscapeClientState.reset();
        EclipseTabRenderer.reset();
    }

    public static boolean isCustomTabVisible() {
        return EclipseTabRenderer.isVisible(Minecraft.getInstance());
    }

    public static void setTabRequested(boolean requested) {
        EclipseTabRenderer.setRequested(requested);
    }

    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) {
            return;
        }
        if (EclipseTabRenderer.isVisible(client)) {
            refreshFallbackRoster(client);
            EclipseTabRenderer.render(graphics, width, height, client, TAB_ROSTER);
        }
        if (client.screen == null && !isCustomTabVisible()) {
            Player target = targetedPlayer(client);
            if (target != null) {
                LabelInfo label = LABELS.get(target.getId());
                if (label != null) {
                    int color = label.known ? 0xFFE3C099 : label.note == null || label.note.isBlank() ? 0xFF8F8F8F : 0xFF7FD0CC;
                    int x = width / 2;
                    int y = height / 2 + 32;
                    String stateLine = label.statusLine();
                    int textWidth = Math.max(client.font.width(label.label), client.font.width(stateLine));
                    int boxWidth = Math.max(112, textWidth + 22);
                    int boxBottom = y + (stateLine.isBlank() ? 15 : 27);
                    graphics.fill(x - boxWidth / 2, y - 7, x + boxWidth / 2, boxBottom, 0xA812100E);
                    graphics.fill(x - boxWidth / 2, y - 7, x + boxWidth / 2, y - 6, label.known ? 0xAAE3C099 : 0x887FD0CC);
                    graphics.centeredText(client.font, label.label, x, y, color);
                    if (!stateLine.isBlank()) {
                        graphics.centeredText(client.font, stateLine, x, y + 12, 0xFFA5C3C4);
                    }
                    graphics.centeredText(client.font, "G - взаимодействие", x, y + (stateLine.isBlank() ? 17 : 29), 0x99D8D1C8);
                }
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
        EscapeClientState.render(graphics, width, height);
    }

    public static void openNote(UUID target, String label) {
        Minecraft.getInstance().setScreen(new AcquaintanceInputScreen(target, 5, "Записать примету", ""));
    }

    public static void greet(UUID target) {
        send(1, target, "");
    }

    public static void interaction(UUID target, int action) {
        send(action, target, "");
    }

    public static void takeSearchItem(UUID target, String key) {
        send(30, target, key);
    }

    public static RoleplayPose poseFor(Player player) {
        if (player == null) {
            return RoleplayPose.NONE;
        }
        PhysicalPose active = PHYSICAL_POSES.get(player.getUUID());
        if (active != null && !active.isExpired()) {
            return active.toRoleplayPose();
        }
        LabelInfo label = LABELS.get(player.getId());
        if (label == null) {
            return RoleplayPose.NONE;
        }
        if (label.escapeMode != null && !label.escapeMode.isBlank()) {
            return new RoleplayPose("ESCAPE_" + label.escapeMode, "HELP_ACTOR".equals(label.escapeMode), 1.0f,
                    label.bound, label.kneeling, label.carried, label.escorting);
        }
        if (label.carried) {
            return RoleplayPose.carriedPose();
        }
        if (label.escorting) {
            return RoleplayPose.escortPose();
        }
        if (label.kneeling) {
            return RoleplayPose.kneelingPose();
        }
        if (label.bound) {
            return RoleplayPose.boundPose();
        }
        return RoleplayPose.NONE;
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

    private static void openSearchResults(JsonObject json) {
        UUID target = UUID.fromString(json.get("target").getAsString());
        String title = json.has("title") ? json.get("title").getAsString() : "Обыск";
        JsonArray items = json.has("items") ? json.getAsJsonArray("items") : new JsonArray();
        Minecraft.getInstance().setScreen(new SearchResultsScreen(target, title, items));
    }

    private static void updatePhysicalAction(JsonObject json) {
        UUID actor = UUID.fromString(json.get("actor").getAsString());
        UUID target = UUID.fromString(json.get("target").getAsString());
        String phase = json.get("phase").getAsString();
        if (!"start".equals(phase)) {
            PHYSICAL_POSES.remove(actor);
            PHYSICAL_POSES.remove(target);
            return;
        }
        String action = json.get("action").getAsString();
        long durationMs = Math.max(400L, json.has("durationMs") ? json.get("durationMs").getAsLong() : 1200L);
        long now = System.currentTimeMillis();
        PHYSICAL_POSES.put(actor, new PhysicalPose(action, true, now, durationMs));
        PHYSICAL_POSES.put(target, new PhysicalPose(action, false, now, durationMs));
    }

    private static void updateState(JsonArray players, JsonArray tab) {
        LABELS.clear();
        if (players != null) {
            for (int i = 0; i < players.size(); i++) {
                JsonObject p = players.get(i).getAsJsonObject();
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.player != null && p.get("entityId").getAsInt() == client.player.getId()) {
                    EscapeClientState.updateCapabilities(p);
                }
                LABELS.put(p.get("entityId").getAsInt(), new LabelInfo(
                        UUID.fromString(p.get("uuid").getAsString()),
                        p.get("label").getAsString(),
                        p.get("known").getAsBoolean(),
                        p.has("note") ? p.get("note").getAsString() : "",
                        p.has("masked") && p.get("masked").getAsBoolean(),
                        p.has("bound") && p.get("bound").getAsBoolean(),
                        p.has("kneeling") && p.get("kneeling").getAsBoolean(),
                        p.has("carried") && p.get("carried").getAsBoolean(),
                        p.has("escorting") && p.get("escorting").getAsBoolean(),
                        p.has("escapeMode") ? p.get("escapeMode").getAsString() : ""
                ));
            }
        }

        List<TabRoster.Entry> entries = new ArrayList<>();
        if (tab != null) {
            for (int i = 0; i < tab.size(); i++) {
                JsonObject p = tab.get(i).getAsJsonObject();
                entries.add(new TabRoster.Entry(UUID.fromString(p.get("uuid").getAsString()),
                        p.get("label").getAsString(), p.has("self") && p.get("self").getAsBoolean()));
            }
        }
        TAB_ROSTER.replace(entries, tab != null);
        nextFallbackRefreshNanos = 0;
    }

    private static String labelFor(Player player) {
        LabelInfo info = LABELS.get(player.getId());
        if (info != null) {
            return info.label;
        }
        Component display = player.getDisplayName();
        return display == null ? "Незнакомец" : display.getString();
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

    /** Safe compatibility path while waiting for the personalized full-server roster. */
    private static void refreshFallbackRoster(Minecraft client) {
        if (TAB_ROSTER.authoritative()) return;
        long now = System.nanoTime();
        if (now < nextFallbackRefreshNanos) return;
        nextFallbackRefreshNanos = now + 500_000_000L;
        List<TabRoster.Entry> entries = new ArrayList<>();
        if (client.getConnection() != null) {
            for (var online : client.getConnection().getOnlinePlayers()) {
                UUID id = online.getProfile().id();
                boolean self = id.equals(client.player.getUUID());
                Player nearby = client.level.getPlayerByUUID(id);
                LabelInfo info = nearby == null ? null : LABELS.get(nearby.getId());
                String label = self ? selfName(client.player) : info == null ? "Незнакомец" : info.label;
                entries.add(new TabRoster.Entry(id, label, self));
            }
        }
        if (entries.isEmpty()) entries.add(new TabRoster.Entry(client.player.getUUID(), selfName(client.player), true));
        TAB_ROSTER.replace(entries, false);
    }

    private static String selfName(Player player) {
        Component display = player.getDisplayName();
        return display == null ? player.getName().getString() : display.getString();
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

    private record LabelInfo(UUID uuid, String label, boolean known, String note, boolean masked, boolean bound, boolean kneeling, boolean carried, boolean escorting, String escapeMode) {
        private String statusLine() {
            if (escapeMode != null && !escapeMode.isBlank()) {
                return escapeMode.startsWith("HELP") ? "освобождает пленника" : "пытается освободиться";
            }
            if (carried) {
                return "тащат";
            }
            if (escorting) {
                return "ведет";
            }
            if (kneeling) {
                return "на коленях";
            }
            if (bound) {
                return "связаны руки";
            }
            return "";
        }
    }

    private record PhysicalPose(String action, boolean actor, long startedAt, long durationMs) {
        private boolean isExpired() {
            return System.currentTimeMillis() - startedAt > durationMs + 280L;
        }

        private RoleplayPose toRoleplayPose() {
            float progress = Math.min(1.0f, Math.max(0.0f, (System.currentTimeMillis() - startedAt) / (float) durationMs));
            return new RoleplayPose(action, actor, progress, false, false, false, false);
        }
    }

    public record RoleplayPose(String action, boolean actor, float progress, boolean bound, boolean kneeling, boolean carried, boolean escorting) {
        public static final RoleplayPose NONE = new RoleplayPose("", false, 0.0f, false, false, false, false);

        private static RoleplayPose boundPose() {
            return new RoleplayPose("", false, 0.0f, true, false, false, false);
        }

        private static RoleplayPose kneelingPose() {
            return new RoleplayPose("", false, 0.0f, false, true, false, false);
        }

        private static RoleplayPose carriedPose() {
            return new RoleplayPose("", false, 0.0f, false, false, true, false);
        }

        private static RoleplayPose escortPose() {
            return new RoleplayPose("", false, 0.0f, false, false, false, true);
        }

        public boolean active() {
            return action != null && !action.isBlank();
        }
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
        private final int acceptAction;
        private final int declineAction;
        private int ticks;

        private PendingRequest(UUID from, String text, String hint, int acceptAction, int declineAction, int ticks) {
            this.from = from;
            this.text = text;
            this.hint = hint;
            this.acceptAction = acceptAction;
            this.declineAction = declineAction;
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
