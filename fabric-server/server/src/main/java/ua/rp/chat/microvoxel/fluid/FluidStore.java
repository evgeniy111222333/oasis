package ua.rp.chat.microvoxel.fluid;

import ua.rp.chat.microvoxel.FluidVolume;
import ua.rp.chat.microvoxel.MicrovoxelKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Store for voxel fluid data. Unlike the microvoxel region store this keeps no journal: fluid data
 * is small (4 KB per wet volume, typically dozens of volumes), so a throttled full rewrite is
 * cheaper and simpler than journaling.
 *
 * <p>The live {@link FluidVolume} arrays are mutated in place by the simulation on the server
 * thread, so the persistence worker must never read them. {@link #capturePersistSnapshot()} runs
 * on the server thread and deep-copies the whole state into an immutable {@link PersistSnapshot};
 * the worker only ever serialises that snapshot. Every write goes through a temp file plus atomic
 * move, so a crash can only lose the last unwritten window, never corrupt the file.</p>
 */
public final class FluidStore {
    private static final int MAGIC = 0x4D564631;
    /**
     * Version 2 adds one kind byte per entry (0 water, 1 lava). Version 1 files load
     * transparently with every volume defaulting to water — the only fluid that existed.
     */
    private static final int VERSION = 2;
    /** Upper bound on wet volumes; bounds file size and load time under abuse. */
    public static final int MAX_ENTRIES = 65_536;

    private final Map<MicrovoxelKey, FluidVolume> fluids = new HashMap<>();
    private boolean dirty;
    private boolean loadedFromBackup;
    private long changeSequence;

    public synchronized FluidVolume get(MicrovoxelKey key) {
        return fluids.get(key);
    }

    /** Returns the live mutable volume. Callers mutate cells directly, then {@link #markDirty}. */
    public synchronized void put(MicrovoxelKey key, FluidVolume volume) {
        if (!fluids.containsKey(key) && fluids.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("Fluid volume limit reached");
        }
        fluids.put(key, volume);
        dirty = true;
        changeSequence++;
    }

    public synchronized FluidVolume remove(MicrovoxelKey key) {
        FluidVolume removed = fluids.remove(key);
        if (removed != null) {
            dirty = true;
            changeSequence++;
        }
        return removed;
    }

    public synchronized void markDirty() {
        dirty = true;
        changeSequence++;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized int size() {
        return fluids.size();
    }

    /**
     * Total water units across all volumes. Linear scan, only for operator status (never on
     * a hot path): this is how seep/purge losses stay visible instead of silent.
     */
    public synchronized long totalUnits() {
        long total = 0;
        for (FluidVolume volume : fluids.values()) total += volume.totalUnits();
        return total;
    }

    /**
     * Live server-thread view of the fluid map. The values are the sim's mutable volumes; callers
     * must run on the server thread and must not persist them (capture a snapshot instead).
     */
    public synchronized Map<MicrovoxelKey, FluidVolume> liveVolumes() {
        return Map.copyOf(fluids);
    }

    /** Server-thread live view; kept as the historical name used across the sim and tests. */
    public synchronized Map<MicrovoxelKey, FluidVolume> snapshot() {
        return Map.copyOf(fluids);
    }

    public synchronized boolean loadedFromBackup() {
        return loadedFromBackup;
    }

    /**
     * Deep-copies the whole state into an immutable snapshot on the server thread. The worker
     * serialises this snapshot, so a concurrent sim edit can never tear the persisted bytes.
     */
    public synchronized PersistSnapshot capturePersistSnapshot() {
        List<PersistSnapshot.Entry> entries = new ArrayList<>(fluids.size());
        for (Map.Entry<MicrovoxelKey, FluidVolume> entry : fluids.entrySet()) {
            FluidVolume volume = entry.getValue();
            entries.add(new PersistSnapshot.Entry(entry.getKey(), volume.revision(),
                    volume.kind(), volume.levelsCopy()));
        }
        return new PersistSnapshot(changeSequence, List.copyOf(entries));
    }

    /**
     * Clears the dirty flag only when nothing changed since the captured sequence, so an edit
     * applied while the worker was writing is not silently dropped from the next save.
     */
    public synchronized void acknowledgePersisted(long sequence) {
        if (sequence >= changeSequence) {
            dirty = false;
        }
    }

    /** Loads the store, falling back to the backup file once before starting empty. */
    public synchronized void load(Path file) throws IOException {
        fluids.clear();
        dirty = false;
        loadedFromBackup = false;
        changeSequence = 0L;
        if (!Files.isRegularFile(file)) return;
        try {
            readEntries(file);
            return;
        } catch (IOException primaryInvalid) {
            Path backup = backupFile(file);
            if (!Files.isRegularFile(backup)) throw primaryInvalid;
            readEntries(backup);
            loadedFromBackup = true;
        }
    }

    private void readEntries(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported fluid storage format");
            int version = input.readInt();
            if (version != 1 && version != VERSION) {
                throw new IOException("Unsupported fluid storage format");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid fluid entry count");
            for (int index = 0; index < count; index++) {
                UUID worldId = new UUID(input.readLong(), input.readLong());
                MicrovoxelKey key = new MicrovoxelKey(worldId, input.readInt(), input.readInt(), input.readInt());
                int revision = input.readInt();
                FluidVolume.Kind kind = FluidVolume.Kind.WATER;
                if (version >= 2) {
                    try {
                        kind = FluidVolume.Kind.fromCode(input.readUnsignedByte());
                    } catch (IllegalArgumentException unknownKind) {
                        throw new IOException("Unknown fluid kind", unknownKind);
                    }
                }
                byte[] levels = input.readNBytes(FluidVolume.CELL_COUNT);
                if (levels.length != FluidVolume.CELL_COUNT) throw new EOFException("Truncated fluid volume");
                if (fluids.put(key, FluidVolume.restore(revision, levels, kind)) != null) {
                    throw new IOException("Duplicate fluid position in storage");
                }
            }
            if (input.read() != -1) throw new IOException("Trailing bytes in fluid storage");
        }
    }

    /**
     * Server-thread convenience: capture, write, then clear the dirty flag (only when nothing
     * changed since the capture). The capture deep-copies under the lock, so this is safe to call
     * on the server thread (periodic/shutdown saves). Never call it from the save worker —
     * capture there would race the sim's in-place array edits.
     */
    public void save(Path file) throws IOException {
        PersistSnapshot snapshot = capturePersistSnapshot();
        writeSnapshot(file, snapshot);
        acknowledgePersisted(snapshot.sequence());
    }

    /**
     * Pure writer: serialises an immutable snapshot through a temp file plus atomic move. Runs on
     * the save worker and reads nothing but the snapshot.
     */
    public static void writeSnapshot(Path file, PersistSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(snapshot.entries().size());
            for (PersistSnapshot.Entry entry : snapshot.entries()) {
                MicrovoxelKey key = entry.key();
                output.writeLong(key.worldId().getMostSignificantBits());
                output.writeLong(key.worldId().getLeastSignificantBits());
                output.writeInt(key.x());
                output.writeInt(key.y());
                output.writeInt(key.z());
                output.writeInt(entry.revision());
                output.writeByte(entry.kind().code());
                output.write(entry.levels());
            }
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.write(temporary, bytes.toByteArray());
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Path backup = backupFile(file);
        Files.deleteIfExists(backup);
        Files.copy(file, backup);
    }

    public record PersistSnapshot(long sequence, List<Entry> entries) {
        public record Entry(MicrovoxelKey key, int revision, FluidVolume.Kind kind, byte[] levels) {
        }
    }

    private static Path backupFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
