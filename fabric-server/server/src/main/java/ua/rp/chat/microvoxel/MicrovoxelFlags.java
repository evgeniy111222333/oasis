package ua.rp.chat.microvoxel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Per-volume protection flags, deliberately stored outside the region format so future flags
 * never require a storage migration. Only {@link #PROTECTED} exists today: a protected volume
 * rejects edits, mining, explosions, fire and portable break/place, and reports itself in the
 * operator status line.
 *
 * <p>Persistence is a small sidecar JSON file next to the region store ({@code
 * microvoxel-flags.json}), loaded once at startup and rewritten on every mutation. Mutations
 * are rare admin operations, so a full-file rewrite is cheaper than a journal. The entry count
 * is capped to bound file size; beyond the cap the oldest entries are evicted first.</p>
 */
public final class MicrovoxelFlags {
    /** Volume rejects every mutation path (edit, mining, explosion, fire, break/place). */
    public static final int PROTECTED = 1;

    /** Hard cap on persisted entries; bounds the sidecar file under admin abuse. */
    static final int MAX_ENTRIES = 65_536;

    private final ConcurrentHashMap<MicrovoxelKey, Integer> flags = new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<MicrovoxelKey> insertionOrder = new java.util.ArrayDeque<>();
    private final Path file;
    private final Logger logger;

    public MicrovoxelFlags(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public boolean isProtected(MicrovoxelKey key) {
        Integer value = flags.get(key);
        return value != null && (value & PROTECTED) != 0;
    }

    public int get(MicrovoxelKey key) {
        Integer value = flags.get(key);
        return value == null ? 0 : value;
    }

    /** Sets flags for one volume (0 clears). Persists immediately; admin-rate only. */
    public synchronized void set(MicrovoxelKey key, int value) {
        if (value == 0) {
            flags.remove(key);
        } else {
            if (!flags.containsKey(key)) {
                insertionOrder.addLast(key);
                evictOverflow();
            }
            flags.put(key, value);
        }
        save();
    }

    public synchronized int size() {
        return flags.size();
    }

    private void evictOverflow() {
        while (flags.size() > MAX_ENTRIES && !insertionOrder.isEmpty()) {
            MicrovoxelKey oldest = insertionOrder.pollFirst();
            if (oldest != null) flags.remove(oldest);
        }
    }

    /** Loads the sidecar file; a missing or corrupt file starts empty (fail-open for flags). */
    public synchronized void load() {
        flags.clear();
        insertionOrder.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (json.isEmpty() || json.equals("{}")) return;
            // Minimal flat-object parser: {"world|x|y|z": flags, ...}. No JSON dependency.
            String body = json.substring(json.indexOf('{') + 1, json.lastIndexOf('}'));
            for (String entry : body.split(",")) {
                String[] parts = entry.split(":", 2);
                if (parts.length != 2) continue;
                MicrovoxelKey key = parseKey(unquote(parts[0].trim()));
                if (key == null) continue;
                int value = Integer.parseInt(parts[1].trim());
                if (value != 0 && flags.size() < MAX_ENTRIES) {
                    flags.put(key, value);
                    insertionOrder.addLast(key);
                }
            }
        } catch (RuntimeException | IOException corrupt) {
            logger.warning("Microvoxel flags file is corrupt; starting with no protected volumes: "
                    + corrupt.getMessage());
            flags.clear();
            insertionOrder.clear();
        }
    }

    private synchronized void save() {
        if (file == null) return;
        try {
            StringBuilder json = new StringBuilder(flags.size() * 48 + 2);
            json.append('{');
            boolean first = true;
            for (Map.Entry<MicrovoxelKey, Integer> entry : flags.entrySet()) {
                if (!first) json.append(',');
                first = false;
                MicrovoxelKey key = entry.getKey();
                json.append('"').append(key.worldId()).append('|')
                        .append(key.x()).append('|').append(key.y()).append('|').append(key.z())
                        .append("\":").append(entry.getValue());
            }
            json.append('}');
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException notAtomic) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            logger.warning("Unable to persist microvoxel flags: " + error.getMessage());
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static MicrovoxelKey parseKey(String value) {
        try {
            String[] parts = value.split("\\|");
            if (parts.length != 4) return null;
            return new MicrovoxelKey(UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
