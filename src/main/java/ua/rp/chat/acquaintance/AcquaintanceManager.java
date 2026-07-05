package ua.rp.chat.acquaintance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import ua.rp.chat.RPChat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AcquaintanceManager implements PluginMessageListener {
    public static final String ACTION_CHANNEL = "rpchat:acq_action";
    public static final String STATE_CHANNEL = "rpchat:acq_state";

    private static final TextColor SAND = TextColor.color(0xE3C099);
    private static final TextColor MUTED = TextColor.color(0x9A9289);
    private static final long REQUEST_TTL_MS = 18_000L;

    private final RPChat plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File storageFile;
    private final Map<UUID, Map<UUID, Entry>> known = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRequest> pendingByTarget = new ConcurrentHashMap<>();
    private boolean dirty;

    public AcquaintanceManager(RPChat plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "acquaintances.json");
    }

    public void start() {
        load();
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, STATE_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ACTION_CHANNEL, this);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    public void shutdown() {
        save();
    }

    public String chatNameFor(Player viewer, Player target) {
        if (viewer == null || target == null || viewer.equals(target)) {
            return plugin.getRpChatService().rpName(target);
        }
        Entry entry = known.getOrDefault(viewer.getUniqueId(), Map.of()).get(target.getUniqueId());
        if (isMasked(target)) {
            return entry != null && entry.name != null && !entry.name.isBlank()
                    ? "Голос " + entry.name
                    : "Голос незнакомца";
        }
        return labelFor(target, entry);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ACTION_CHANNEL.equals(channel) || player == null || message == null) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int action = input.readInt();
            UUID targetId = new UUID(input.readLong(), input.readLong());
            String text = readUtf8(input);
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline() || target.equals(player) || !near(player, target, 4.2)) {
                sendToast(player, "Собеседник слишком далеко.", "muted");
                return;
            }
            switch (action) {
                case 1 -> requestGreeting(player, target);
                case 2 -> acceptGreeting(player, target);
                case 3 -> declineGreeting(player, target);
                case 4 -> completeIntroduction(player, target, text);
                case 5 -> setNote(player, target, text);
                default -> {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        pendingByTarget.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendState(viewer);
        }
        if (dirty) {
            save();
        }
    }

    private void requestGreeting(Player requester, Player target) {
        PendingRequest pending = new PendingRequest(requester.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + REQUEST_TTL_MS);
        pendingByTarget.put(target.getUniqueId(), pending);
        sendToast(requester, "Вы протянули руку для знакомства.", "sand");

        JsonObject json = base("request");
        json.addProperty("from", requester.getUniqueId().toString());
        json.addProperty("text", "Незнакомец протягивает вам руку для приветствия.");
        json.addProperty("hint", "Y - ответить взаимностью, N - проигнорировать");
        send(target, json);
        playHandshakeCue(requester, target);
    }

    private void acceptGreeting(Player target, Player requester) {
        PendingRequest pending = pendingByTarget.get(target.getUniqueId());
        if (pending == null || !pending.requester.equals(requester.getUniqueId())) {
            sendToast(target, "Приглашение уже неактуально.", "muted");
            return;
        }
        JsonObject prompt = base("introduce");
        prompt.addProperty("target", target.getUniqueId().toString());
        prompt.addProperty("defaultName", plugin.getRpChatService().rpName(requester));
        prompt.addProperty("title", "Как вы хотите представиться?");
        send(requester, prompt);
        sendToast(target, "Вы ответили на приветствие.", "sand");
        playHandshakeCue(requester, target);
    }

    private void declineGreeting(Player target, Player requester) {
        pendingByTarget.remove(target.getUniqueId());
        sendToast(target, "Вы проигнорировали приветствие.", "muted");
        sendToast(requester, "Приветствие оставили без ответа.", "muted");
    }

    private void completeIntroduction(Player requester, Player target, String alias) {
        PendingRequest pending = pendingByTarget.remove(target.getUniqueId());
        if (pending == null || !pending.requester.equals(requester.getUniqueId())) {
            sendToast(requester, "Знакомство уже неактуально.", "muted");
            return;
        }

        String targetRealName = plugin.getRpChatService().rpName(target);
        String requesterAlias = sanitize(alias);
        remember(requester, target, targetRealName);
        if (!requesterAlias.isBlank()) {
            remember(target, requester, requesterAlias);
        }

        requester.sendMessage(Component.text("* Вы пожали руку незнакомцу и теперь знаете его как " + targetRealName + ".", SAND));
        if (requesterAlias.isBlank()) {
            target.sendMessage(Component.text("* Незнакомец пожал вам руку, но не назвал своего имени.", MUTED));
        } else {
            target.sendMessage(Component.text("* Вы запомнили незнакомца под именем " + requesterAlias + ".", SAND));
        }
        playHandshakeCue(requester, target);
        sendState(requester);
        sendState(target);
    }

    private void setNote(Player viewer, Player target, String note) {
        String clean = sanitize(note);
        if (clean.length() > 42) {
            clean = clean.substring(0, 42).trim();
        }
        Map<UUID, Entry> map = known.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        Entry entry = map.computeIfAbsent(target.getUniqueId(), id -> new Entry("", ""));
        entry.note = clean;
        dirty = true;
        sendToast(viewer, clean.isBlank() ? "Заметка очищена." : "Примета записана: " + clean, "sand");
        sendState(viewer);
    }

    private void remember(Player viewer, Player target, String name) {
        Map<UUID, Entry> map = known.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        Entry entry = map.computeIfAbsent(target.getUniqueId(), id -> new Entry("", ""));
        entry.name = sanitize(name);
        dirty = true;
    }

    private void sendState(Player viewer) {
        JsonObject json = base("state");
        JsonArray players = new JsonArray();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(viewer) || !viewer.getWorld().equals(target.getWorld()) || viewer.getLocation().distanceSquared(target.getLocation()) > 48 * 48) {
                continue;
            }
            Entry entry = known.getOrDefault(viewer.getUniqueId(), Map.of()).get(target.getUniqueId());
            JsonObject p = new JsonObject();
            p.addProperty("uuid", target.getUniqueId().toString());
            p.addProperty("entityId", target.getEntityId());
            p.addProperty("label", labelFor(target, entry));
            p.addProperty("known", entry != null && entry.name != null && !entry.name.isBlank());
            p.addProperty("note", entry == null ? "" : entry.note);
            p.addProperty("masked", isMasked(target));
            players.add(p);
        }
        json.add("players", players);
        send(viewer, json);
    }

    private String labelFor(Player target, Entry entry) {
        if (isMasked(target)) {
            return "Человек в закрытом шлеме";
        }
        if (entry != null && entry.name != null && !entry.name.isBlank()) {
            return entry.name;
        }
        String note = entry == null ? "" : entry.note;
        return note == null || note.isBlank()
                ? "Незнакомец [ID: " + Math.floorMod(target.getUniqueId().hashCode(), 1000) + "]"
                : "Незнакомец (\"" + note + "\")";
    }

    private boolean isMasked(Player player) {
        if (player.getInventory().getHelmet() == null) {
            return false;
        }
        String type = player.getInventory().getHelmet().getType().name();
        return type.contains("HELMET") || type.contains("SKULL") || type.contains("HEAD");
    }

    private void sendToast(Player player, String text, String tone) {
        JsonObject json = base("toast");
        json.addProperty("text", text);
        json.addProperty("tone", tone);
        send(player, json);
    }

    private void playHandshakeCue(Player a, Player b) {
        a.swingMainHand();
        b.swingMainHand();

        JsonObject jsonA = base("handshake");
        jsonA.addProperty("target", b.getUniqueId().toString());
        JsonObject jsonB = base("handshake");
        jsonB.addProperty("target", a.getUniqueId().toString());
        send(a, jsonA);
        send(b, jsonB);
    }

    private boolean near(Player a, Player b, double distance) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= distance * distance && a.hasLineOfSight(b);
    }

    private JsonObject base(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    private void send(Player player, JsonObject json) {
        try {
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeVarInt(out, data.length);
            out.write(data);
            player.sendPluginMessage(plugin, STATE_CHANNEL, out.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private String readUtf8(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length <= 0) {
            return "";
        }
        byte[] bytes = input.readNBytes(Math.min(length, 32760));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String sanitize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private void load() {
        if (!storageFile.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(storageFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (String viewerKey : root.keySet()) {
                UUID viewer = UUID.fromString(viewerKey);
                Map<UUID, Entry> map = new ConcurrentHashMap<>();
                JsonObject entries = root.getAsJsonObject(viewerKey);
                for (String targetKey : entries.keySet()) {
                    JsonObject raw = entries.getAsJsonObject(targetKey);
                    map.put(UUID.fromString(targetKey), new Entry(
                            raw.has("name") ? raw.get("name").getAsString() : "",
                            raw.has("note") ? raw.get("note").getAsString() : ""
                    ));
                }
                known.put(viewer, map);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load acquaintances: " + e.getMessage());
        }
    }

    private void save() {
        dirty = false;
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Map<UUID, Entry>> viewer : known.entrySet()) {
                JsonObject entries = new JsonObject();
                for (Map.Entry<UUID, Entry> target : viewer.getValue().entrySet()) {
                    JsonObject raw = new JsonObject();
                    raw.addProperty("name", target.getValue().name);
                    raw.addProperty("note", target.getValue().note);
                    entries.add(target.getKey().toString(), raw);
                }
                root.add(viewer.getKey().toString(), entries);
            }
            try (FileWriter writer = new FileWriter(storageFile, StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save acquaintances: " + e.getMessage());
        }
    }

    private static final class Entry {
        private String name;
        private String note;

        private Entry(String name, String note) {
            this.name = name;
            this.note = note;
        }
    }

    private record PendingRequest(UUID requester, UUID target, long expiresAt) {
    }
}
