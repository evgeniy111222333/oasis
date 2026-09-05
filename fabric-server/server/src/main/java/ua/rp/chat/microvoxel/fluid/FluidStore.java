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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Whole-file atomic persistence for voxel fluid data. Unlike the microvoxel region store this
 * keeps no journal: fluid data is small (4KB per wet volume, typically dozens of volumes), so
 * a throttled full rewrite is cheaper and simpler than journaling. Every save goes through a
 * temp file plus atomic move, so a crash can only lose the last unwritten window, never
 * corrupt the file.
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
    }

    public synchronized FluidVolume remove(MicrovoxelKey key) {
        FluidVolume removed = fluids.remove(key);
        if (removed != null) dirty = true;
        return removed;
    }

    public synchronized void markDirty() {
        dirty = true;
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

    public synchronized Map<MicrovoxelKey, FluidVolume> snapshot() {
        return Map.copyOf(fluids);
    }

    public synchronized boolean loadedFromBackup() {
        return loadedFromBackup;
    }

    /** Loads the store, falling back to the backup file once before starting empty. */
    public synchronized void load(Path file) throws IOException {
        fluids.clear();
        dirty = false;
        loadedFromBackup = false;
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

    /** Atomic full rewrite; clears the dirty flag only on success. */
    public synchronized void save(Path file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(fluids.size());
            for (Map.Entry<MicrovoxelKey, FluidVolume> entry : fluids.entrySet()) {
                MicrovoxelKey key = entry.getKey();
                output.writeLong(key.worldId().getMostSignificantBits());
                output.writeLong(key.worldId().getLeastSignificantBits());
                output.writeInt(key.x());
                output.writeInt(key.y());
                output.writeInt(key.z());
                FluidVolume volume = entry.getValue();
                output.writeInt(volume.revision());
                output.writeByte(volume.kind().code());
                output.write(volume.levelsCopy());
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
        dirty = false;
    }

    private static Path backupFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
