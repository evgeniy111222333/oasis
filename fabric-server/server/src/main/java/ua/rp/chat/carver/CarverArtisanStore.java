package ua.rp.chat.carver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent artisan progression: mastery points, reputation, finished-piece count, best score
 * and the geology codex (which grain families this artisan has discovered). A tiny JSON ledger
 * written atomically next to the other RPChat config, keyed by player UUID.
 */
public final class CarverArtisanStore {
    /** Per-player progression. Fields are public for the JSON writer only. */
    public static final class Profile {
        public int mastery;
        public int reputation;
        public int pieces;
        public double bestScore;
        public final java.util.LinkedHashSet<String> codex = new java.util.LinkedHashSet<>();

        /** Mastery rank derived from accumulated points. */
        public String rank() {
            if (mastery >= 4000) return "Магистр";
            if (mastery >= 1500) return "Мастер";
            if (mastery >= 500) return "Подмастерье";
            if (mastery >= 100) return "Ученик";
            return "Новичок";
        }
    }

    private final Path file;
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

    public CarverArtisanStore(Path file) {
        this.file = file;
        load();
    }

    public Profile profile(UUID playerId) {
        return profiles.computeIfAbsent(playerId.toString(), key -> new Profile());
    }

    /** Records one finished piece; returns the codex key when a new grain family is discovered. */
    public String record(UUID playerId, CarverEvaluation.Result result, CarverGrainField.GrainType grain) {
        Profile profile = profile(playerId);
        profile.pieces++;
        profile.mastery += result.mastery();
        profile.reputation += (int) Math.round(result.score() * 12.0);
        if (result.score() > profile.bestScore) profile.bestScore = result.score();
        boolean fresh = grain != null && profile.codex.add(grain.name());
        save();
        return fresh ? grain.name() : null;
    }

    private synchronized void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject node = entry.getValue().getAsJsonObject();
                Profile profile = new Profile();
                profile.mastery = intOr(node, "mastery", 0);
                profile.reputation = intOr(node, "reputation", 0);
                profile.pieces = intOr(node, "pieces", 0);
                profile.bestScore = node.has("bestScore") ? node.get("bestScore").getAsDouble() : 0.0;
                if (node.has("codex") && node.get("codex").isJsonArray()) {
                    for (JsonElement element : node.getAsJsonArray("codex")) {
                        profile.codex.add(element.getAsString());
                    }
                }
                profiles.put(entry.getKey(), profile);
            }
        } catch (RuntimeException | IOException ignored) {
            // A corrupt ledger must never stop the carver; it simply starts empty.
        }
    }

    /** Atomic persist: write a sibling temp file, then move it over the live ledger. */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            Map<String, Profile> snapshot = new LinkedHashMap<>(profiles);
            for (Map.Entry<String, Profile> entry : snapshot.entrySet()) {
                Profile profile = entry.getValue();
                JsonObject node = new JsonObject();
                node.addProperty("mastery", profile.mastery);
                node.addProperty("reputation", profile.reputation);
                node.addProperty("pieces", profile.pieces);
                node.addProperty("bestScore", profile.bestScore);
                var codex = new com.google.gson.JsonArray();
                for (String key : profile.codex) codex.add(key);
                node.add("codex", codex);
                root.add(entry.getKey(), node);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // Progression is best-effort; a failed write must not break carving.
        }
    }

    private static int intOr(JsonObject node, String key, int fallback) {
        return node.has(key) ? node.get(key).getAsInt() : fallback;
    }
}
