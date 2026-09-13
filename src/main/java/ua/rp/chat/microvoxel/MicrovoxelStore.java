package ua.rp.chat.microvoxel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MicrovoxelStore {
    private static final int MAGIC = 0x4D565831;
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 1_000_000;

    private final Path file;
    private final Map<MicrovoxelKey, MicrovoxelVolume> volumes = new HashMap<>();
    private final Map<ChunkKey, Set<MicrovoxelKey>> chunks = new HashMap<>();
    private boolean loadedFromBackup;

    public MicrovoxelStore(Path file) {
        this.file = file;
    }

    public synchronized MicrovoxelVolume get(MicrovoxelKey key) {
        return volumes.get(key);
    }

    public synchronized void put(MicrovoxelKey key, MicrovoxelVolume volume) {
        volumes.put(key, volume);
        chunks.computeIfAbsent(ChunkKey.of(key), ignored -> new HashSet<>()).add(key);
    }

    public synchronized MicrovoxelVolume remove(MicrovoxelKey key) {
        MicrovoxelVolume removed = volumes.remove(key);
        Set<MicrovoxelKey> indexed = chunks.get(ChunkKey.of(key));
        if (indexed != null) {
            indexed.remove(key);
            if (indexed.isEmpty()) chunks.remove(ChunkKey.of(key));
        }
        return removed;
    }

    public synchronized int size() {
        return volumes.size();
    }

    public synchronized int countInChunk(UUID worldId, int chunkX, int chunkZ) {
        return chunks.getOrDefault(new ChunkKey(worldId, chunkX, chunkZ), Set.of()).size();
    }

    public synchronized List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> inChunk(UUID worldId, int chunkX, int chunkZ) {
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> result = new ArrayList<>();
        for (MicrovoxelKey key : chunks.getOrDefault(new ChunkKey(worldId, chunkX, chunkZ), Set.of())) {
            MicrovoxelVolume volume = volumes.get(key);
            if (volume != null) result.add(Map.entry(key, volume));
        }
        return result;
    }

    public synchronized List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> nearby(
            UUID worldId, int chunkX, int chunkZ, int radius) {
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> result = new ArrayList<>();
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                result.addAll(inChunk(worldId, x, z));
            }
        }
        return result;
    }

    public synchronized void load() throws IOException {
        volumes.clear();
        chunks.clear();
        loadedFromBackup = false;
        Path backup = backupFile();
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> loaded;
        if (!Files.isRegularFile(file)) {
            if (!Files.isRegularFile(backup)) return;
            loaded = readEntries(backup);
            loadedFromBackup = true;
        } else {
            try {
                loaded = readEntries(file);
            } catch (IOException | RuntimeException primaryFailure) {
                if (!Files.isRegularFile(backup)) throw primaryFailure;
                try {
                    loaded = readEntries(backup);
                    loadedFromBackup = true;
                } catch (IOException | RuntimeException backupFailure) {
                    primaryFailure.addSuppressed(backupFailure);
                    throw primaryFailure;
                }
            }
        }
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : loaded) put(entry.getKey(), entry.getValue());
    }

    public synchronized boolean loadedFromBackup() {
        return loadedFromBackup;
    }

    private List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> readEntries(Path source) throws IOException {
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> loaded = new ArrayList<>();
        Set<MicrovoxelKey> unique = new HashSet<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported microvoxel storage format");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid microvoxel entry count: " + count);
            for (int index = 0; index < count; index++) {
                UUID worldId = new UUID(input.readLong(), input.readLong());
                MicrovoxelKey key = new MicrovoxelKey(worldId, input.readInt(), input.readInt(), input.readInt());
                int revision = input.readInt();
                int paletteSize = input.readUnsignedByte();
                if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
                    throw new IOException("Invalid palette size " + paletteSize);
                }
                List<String> palette = new ArrayList<>(paletteSize);
                for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
                    int length = input.readUnsignedShort();
                    byte[] utf8 = input.readNBytes(length);
                    if (utf8.length != length) throw new EOFException("Truncated palette entry");
                    palette.add(new String(utf8, java.nio.charset.StandardCharsets.UTF_8));
                }
                byte[] cells = input.readNBytes(MicrovoxelVolume.CELL_COUNT);
                if (cells.length != MicrovoxelVolume.CELL_COUNT) throw new EOFException("Truncated cell volume");
                if (!unique.add(key)) throw new IOException("Duplicate microvoxel position in storage");
                loaded.add(Map.entry(key, MicrovoxelVolume.restore(revision, palette, cells)));
            }
            if (input.read() != -1) throw new IOException("Trailing bytes in microvoxel storage");
        }
        return loaded;
    }

    public synchronized Snapshot snapshot() {
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries = new ArrayList<>(volumes.size());
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : volumes.entrySet()) {
            entries.add(Map.entry(entry.getKey(), entry.getValue().copy()));
        }
        return new Snapshot(List.copyOf(entries));
    }

    public void save() throws IOException {
        save(snapshot());
    }

    public synchronized void save(Snapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(snapshot.entries.size());
            for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : snapshot.entries) {
                MicrovoxelKey key = entry.getKey();
                MicrovoxelVolume volume = entry.getValue();
                output.writeLong(key.worldId().getMostSignificantBits());
                output.writeLong(key.worldId().getLeastSignificantBits());
                output.writeInt(key.x());
                output.writeInt(key.y());
                output.writeInt(key.z());
                output.writeInt(volume.revision());
                output.writeByte(volume.palette().size());
                for (String material : volume.palette()) {
                    byte[] utf8 = material.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (utf8.length > 65535) throw new IOException("Palette entry is too long");
                    output.writeShort(utf8.length);
                    output.write(utf8);
                }
                output.write(volume.cellsCopy());
            }
        }
        if (Files.isRegularFile(file) && !loadedFromBackup) {
            Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        loadedFromBackup = false;
    }

    public record Snapshot(List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries) {
    }

    private Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
