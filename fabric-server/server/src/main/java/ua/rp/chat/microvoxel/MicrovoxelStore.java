package ua.rp.chat.microvoxel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

public final class MicrovoxelStore {
    private static final int MAGIC = 0x4D565831;
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int JOURNAL_MAGIC = 0x4D564A31;
    private static final int JOURNAL_VERSION = 1;
    private static final int MAX_JOURNAL_BATCH_BYTES = 64 * 1024 * 1024;
    private static final long COMPACT_AFTER_BYTES = 32L * 1024L * 1024L;
    private static final int REGION_MAGIC = 0x4D565232;
    private static final int REGION_VERSION = 2;
    private static final int REGION_CHUNKS = 32;
    private static final int MAX_LOADED_REGIONS = 96;

    private final Path file;
    private final Path regionDirectory;
    private final Map<MicrovoxelKey, MicrovoxelVolume> volumes = new HashMap<>();
    private final Map<ChunkKey, Integer> chunks = new HashMap<>();
    private final Map<RegionKey, RegionMeta> regions = new HashMap<>();
    private final LinkedHashMap<RegionKey, RegionData> loadedRegions =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Set<RegionKey> dirtyRegions = new HashSet<>();
    private final Map<MicrovoxelKey, DirtyState> dirty = new HashMap<>();
    private boolean loadedFromBackup;
    private boolean recoveredJournalTail;
    private long changeSequence;
    private int totalEntries;

    public MicrovoxelStore(Path file) {
        this.file = file;
        this.regionDirectory = file.resolveSibling(file.getFileName() + ".regions-v2");
    }

    public synchronized MicrovoxelVolume get(MicrovoxelKey key) {
        ensureRegionLoaded(RegionKey.of(key));
        return volumes.get(key);
    }

    public synchronized void put(MicrovoxelKey key, MicrovoxelVolume volume) {
        RegionKey regionKey = RegionKey.of(key);
        RegionData region = ensureRegionLoaded(regionKey);
        MicrovoxelVolume previous = volumes.put(key, volume);
        region.keys.add(key);
        if (previous == null) {
            totalEntries++;
            chunks.merge(ChunkKey.of(key), 1, Integer::sum);
            region.meta.entryCount++;
            region.meta.chunkCounts.merge(ChunkKey.of(key), 1, Integer::sum);
        }
        dirtyRegions.add(regionKey);
    }

    public synchronized MicrovoxelVolume remove(MicrovoxelKey key) {
        RegionKey regionKey = RegionKey.of(key);
        RegionData region = ensureRegionLoaded(regionKey);
        MicrovoxelVolume removed = volumes.remove(key);
        if (removed != null) {
            region.keys.remove(key);
            totalEntries--;
            decrement(chunks, ChunkKey.of(key));
            region.meta.entryCount--;
            decrement(region.meta.chunkCounts, ChunkKey.of(key));
            dirtyRegions.add(regionKey);
        }
        return removed;
    }

    /** Records the authoritative post-mutation state for incremental persistence. */
    public synchronized void markDirty(MicrovoxelKey key) {
        ensureRegionLoaded(RegionKey.of(key));
        dirtyRegions.add(RegionKey.of(key));
        dirty.put(key, new DirtyState(++changeSequence));
    }

    public synchronized int size() {
        return totalEntries;
    }

    synchronized int loadedRegionCount() {
        return loadedRegions.size();
    }

    synchronized int indexedRegionCount() {
        return regions.size();
    }

    /** Invoked after a server tick, once no caller retains a mutable borrowed volume. */
    public synchronized void trimCache() {
        evictCleanRegions();
    }

    public synchronized int countInChunk(UUID worldId, int chunkX, int chunkZ) {
        return chunks.getOrDefault(new ChunkKey(worldId, chunkX, chunkZ), 0);
    }

    public synchronized List<ChunkKey> indexedChunks() {
        return List.copyOf(chunks.keySet());
    }

    public synchronized List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> inChunk(UUID worldId, int chunkX, int chunkZ) {
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> result = new ArrayList<>();
        RegionData region = ensureRegionLoaded(new RegionKey(worldId,
                Math.floorDiv(chunkX, REGION_CHUNKS), Math.floorDiv(chunkZ, REGION_CHUNKS)));
        for (MicrovoxelKey key : region.keys) {
            if (key.chunkX() != chunkX || key.chunkZ() != chunkZ) continue;
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

    /** One-time migration from the legacy dimension-only identity to a save-scoped identity. */
    public synchronized int remapWorld(UUID oldWorldId, UUID newWorldId) {
        if (oldWorldId.equals(newWorldId)) return 0;
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> moving = new ArrayList<>();
        List<RegionKey> oldRegions = regions.keySet().stream()
                .filter(key -> key.worldId.equals(oldWorldId)).toList();
        for (RegionKey region : oldRegions) {
            RegionData data = ensureRegionLoaded(region);
            for (MicrovoxelKey key : List.copyOf(data.keys)) {
                MicrovoxelVolume volume = volumes.get(key);
                if (volume != null) moving.add(Map.entry(key, volume));
            }
        }
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : moving) {
            MicrovoxelKey oldKey = entry.getKey();
            remove(oldKey);
            MicrovoxelKey newKey = new MicrovoxelKey(newWorldId,
                    oldKey.x(), oldKey.y(), oldKey.z());
            if (!volumes.containsKey(newKey)) put(newKey, entry.getValue());
        }
        return moving.size();
    }

    public synchronized void load() throws IOException {
        volumes.clear();
        chunks.clear();
        regions.clear();
        loadedRegions.clear();
        dirtyRegions.clear();
        dirty.clear();
        loadedFromBackup = false;
        recoveredJournalTail = false;
        changeSequence = 0L;
        totalEntries = 0;

        if (Files.isDirectory(regionDirectory)
                && (Files.isRegularFile(regionDirectory.resolve("FORMAT")) || hasRegionFiles())) {
            scanRegionIndexes();
        } else {
            List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> legacy = loadLegacyEntries();
            for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : legacy) {
                put(entry.getKey(), entry.getValue());
            }
            if (!legacy.isEmpty()) {
                flushDirtyRegions();
                Files.createDirectories(regionDirectory);
                Files.writeString(regionDirectory.resolve("FORMAT"),
                        "eclipse-microvoxel-regions-v2\n",
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
        replayJournal();
        evictCleanRegions();
    }

    public synchronized boolean loadedFromBackup() {
        return loadedFromBackup;
    }

    public synchronized boolean recoveredJournalTail() {
        return recoveredJournalTail;
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
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries = new ArrayList<>();
        for (RegionKey key : regions.keySet()) {
            RegionData region = ensureRegionLoaded(key);
            for (MicrovoxelKey volumeKey : region.keys) {
                MicrovoxelVolume volume = volumes.get(volumeKey);
                if (volume != null) entries.add(Map.entry(volumeKey, volume.copy()));
            }
        }
        return new Snapshot(List.copyOf(entries));
    }

    public synchronized DirtyBatch snapshotDirty() {
        if (dirty.isEmpty()) return new DirtyBatch(List.of());
        List<DirtyEntry> entries = new ArrayList<>(dirty.size());
        for (Map.Entry<MicrovoxelKey, DirtyState> entry : dirty.entrySet()) {
            MicrovoxelVolume volume = volumes.get(entry.getKey());
            entries.add(new DirtyEntry(entry.getKey(), volume == null ? null : volume.copy(),
                    entry.getValue().sequence()));
        }
        return new DirtyBatch(List.copyOf(entries));
    }

    public synchronized void acknowledge(DirtyBatch batch) {
        for (DirtyEntry entry : batch.entries()) {
            DirtyState current = dirty.get(entry.key());
            if (current != null && current.sequence() <= entry.sequence()) {
                dirty.remove(entry.key());
            }
        }
    }

    public synchronized boolean hasDirtyEntries() {
        return !dirty.isEmpty();
    }

    /** Backlog depth for status/metrics: volumes awaiting journal persistence. */
    public synchronized int dirtyCount() {
        return dirty.size();
    }

    /** Current journal size in bytes; growth here means compacts are not keeping up. */
    public synchronized long journalSizeBytes() {
        try {
            Path journal = journalFile();
            return Files.isRegularFile(journal) ? Files.size(journal) : 0L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    public boolean shouldCompactJournal() {
        try {
            return Files.size(journalFile()) >= COMPACT_AFTER_BYTES;
        } catch (IOException ignored) {
            return false;
        }
    }

    public synchronized void appendJournal(DirtyBatch batch) throws IOException {
        if (batch.entries().isEmpty()) return;
        Files.createDirectories(file.getParent());
        byte[] body = encodeJournalBatch(batch);
        MicrovoxelMetrics.inc("store.journal.appends");
        MicrovoxelMetrics.add("store.journal.bytes", body.length);
        CRC32 crc = new CRC32();
        crc.update(body);
        Path journal = journalFile();
        boolean newFile = !Files.isRegularFile(journal) || Files.size(journal) == 0L;
        try (FileChannel channel = FileChannel.open(journal,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(Channels.newOutputStream(channel)))) {
            if (newFile) {
                output.writeInt(JOURNAL_MAGIC);
                output.writeInt(JOURNAL_VERSION);
            }
            output.writeInt(body.length);
            output.write(body);
            output.writeInt((int) crc.getValue());
            output.flush();
            channel.force(true);
        }
    }

    public void save() throws IOException {
        save(new Snapshot(List.of()));
    }

    public synchronized void save(Snapshot snapshot) throws IOException {
        save(Integer.MAX_VALUE);
    }

    /**
     * Persists journaled state, flushing at most {@code maxRegions} region files. Callers loop
     * until {@link #dirtyRegionCount()} reaches zero; each slice is crash-safe on its own
     * because the journal is only deleted after the slice it covers is durable... note the
     * journal delete below covers the whole journal, so incremental callers must only delete
     * once the final slice lands — see {@link #saveIncrementalSlice(int)}.
     */
    public synchronized void save(int maxRegions) throws IOException {
        flushDirtyRegions(maxRegions);
        Files.createDirectories(regionDirectory);
        Files.writeString(regionDirectory.resolve("FORMAT"),
                "eclipse-microvoxel-regions-v2\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        loadedFromBackup = false;
        Files.deleteIfExists(journalFile());
        evictCleanRegions();
    }

    /**
     * One incremental compaction slice: writes up to {@code maxRegions} dirty regions WITHOUT
     * deleting the journal (unflushed tail may still reference it). Returns true when fully
     * clean; the finalizer must then call {@link #finishIncrementalSave()} to publish FORMAT,
     * drop the journal and evict.
     */
    public synchronized boolean saveIncrementalSlice(int maxRegions) throws IOException {
        flushDirtyRegions(maxRegions);
        MicrovoxelMetrics.inc("store.slices");
        return dirtyRegions.isEmpty();
    }

    /** Publishes an incremental compaction: FORMAT marker, journal drop, cache eviction. */
    public synchronized void finishIncrementalSave() throws IOException {
        Files.createDirectories(regionDirectory);
        Files.writeString(regionDirectory.resolve("FORMAT"),
                "eclipse-microvoxel-regions-v2\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        loadedFromBackup = false;
        Files.deleteIfExists(journalFile());
        evictCleanRegions();
    }

    /**
     * Copies the whole storage directory (regions, flags, journal) into a timestamped backup
     * folder and prunes older backups beyond {@code keep}. Pure file work, no store state
     * touched; safe to run on the save worker. Returns the backup directory.
     */
    public static Path backupDirectory(Path storageDirectory, Path backupRoot, int keep) throws IOException {
        if (!Files.isDirectory(storageDirectory)) {
            throw new IOException("Microvoxel storage directory is missing: " + storageDirectory);
        }
        String stamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = backupRoot.resolve("microvoxels-" + stamp);
        Files.createDirectories(target);
        try (var stream = Files.walk(storageDirectory)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                Path relative = storageDirectory.relativize(source);
                if (relative.toString().isEmpty()) continue;
                if (source.getFileName().toString().endsWith(".tmp")) continue;
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // Prune oldest, keep newest N.
        try (var listing = Files.list(backupRoot)) {
            List<Path> backups = listing
                    .filter(path -> path.getFileName().toString().startsWith("microvoxels-"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (int index = 0; index + keep < backups.size(); index++) {
                deleteRecursively(backups.get(index));
            }
        }
        return target;
    }

    /**
     * Restores a backup over the storage directory. The caller must guarantee no writer is
     * active (server stopped or store shut down): files are replaced atomically per file but
     * the store keeps no lock across the whole tree.
     */
    public static void restoreBackup(Path backup, Path storageDirectory) throws IOException {
        if (!Files.isDirectory(backup)) throw new IOException("Backup is missing: " + backup);
        Files.createDirectories(storageDirectory);
        try (var stream = Files.walk(backup)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                Path relative = backup.relativize(source);
                if (relative.toString().isEmpty()) continue;
                Path destination = storageDirectory.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(temporary, destination,
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException notAtomic) {
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    public record Snapshot(List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries) {
    }

    public record DirtyBatch(List<DirtyEntry> entries) {
    }

    public record DirtyEntry(MicrovoxelKey key, MicrovoxelVolume volume, long sequence) {
    }

    private record DirtyState(long sequence) {
    }

    private static final class RegionMeta {
        private int entryCount;
        private final Map<ChunkKey, Integer> chunkCounts = new HashMap<>();

        private RegionMeta(int entryCount) {
            this.entryCount = entryCount;
        }
    }

    private static final class RegionData {
        private final RegionMeta meta;
        private final Set<MicrovoxelKey> keys = new HashSet<>();

        private RegionData(RegionMeta meta) {
            this.meta = meta;
        }
    }

    private record RegionKey(UUID worldId, int x, int z) {
        private static RegionKey of(MicrovoxelKey key) {
            return new RegionKey(key.worldId(),
                    Math.floorDiv(key.chunkX(), REGION_CHUNKS),
                    Math.floorDiv(key.chunkZ(), REGION_CHUNKS));
        }
    }

    private static <K> void decrement(Map<K, Integer> counts, K key) {
        Integer value = counts.get(key);
        if (value == null) return;
        if (value <= 1) counts.remove(key);
        else counts.put(key, value - 1);
    }

    private boolean hasRegionFiles() throws IOException {
        try (var stream = Files.list(regionDirectory)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".mvr")
                    || path.getFileName().toString().endsWith(".mvr.bak"));
        }
    }

    private List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> loadLegacyEntries() throws IOException {
        Path backup = backupFile();
        if (!Files.isRegularFile(file)) {
            if (!Files.isRegularFile(backup)) return List.of();
            loadedFromBackup = true;
            return readEntries(backup);
        }
        try {
            return readEntries(file);
        } catch (IOException | RuntimeException primaryFailure) {
            if (!Files.isRegularFile(backup)) throw primaryFailure;
            try {
                loadedFromBackup = true;
                return readEntries(backup);
            } catch (IOException | RuntimeException backupFailure) {
                primaryFailure.addSuppressed(backupFailure);
                throw primaryFailure;
            }
        }
    }

    private void scanRegionIndexes() throws IOException {
        Map<RegionKey, Path> candidates = new HashMap<>();
        try (var stream = Files.list(regionDirectory)) {
            stream.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".mvr") || name.endsWith(".mvr.bak");
                    })
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        boolean backup = name.endsWith(".bak");
                        RegionKey key = parseRegionFileName(backup
                                ? name.substring(0, name.length() - 4) : name);
                        if (key != null && (!backup || !candidates.containsKey(key))) {
                            candidates.put(key, path);
                        }
                    });
        }
        for (Map.Entry<RegionKey, Path> entry : candidates.entrySet()) {
            RegionMeta meta;
            try {
                meta = readRegionIndex(entry.getKey());
            } catch (IOException invalidIndex) {
                RegionData data = readRegionWithBackup(entry.getKey());
                meta = data.meta;
                unloadTransientRegion(entry.getKey(), data);
            }
            regions.put(entry.getKey(), meta);
            totalEntries += meta.entryCount;
            for (Map.Entry<ChunkKey, Integer> count : meta.chunkCounts.entrySet()) {
                chunks.merge(count.getKey(), count.getValue(), Integer::sum);
            }
        }
    }

    private RegionData ensureRegionLoaded(RegionKey key) {
        RegionData loaded = loadedRegions.get(key);
        if (loaded != null) return loaded;
        RegionMeta meta = regions.computeIfAbsent(key, ignored -> new RegionMeta(0));
        try {
            loaded = meta.entryCount == 0 ? new RegionData(meta) : readRegionWithBackup(key);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load microvoxel region " + key, error);
        }
        loadedRegions.put(key, loaded);
        return loaded;
    }

    private void evictCleanRegions() {
        if (loadedRegions.size() <= MAX_LOADED_REGIONS) return;
        Iterator<Map.Entry<RegionKey, RegionData>> iterator = loadedRegions.entrySet().iterator();
        while (loadedRegions.size() > MAX_LOADED_REGIONS && iterator.hasNext()) {
            Map.Entry<RegionKey, RegionData> candidate = iterator.next();
            if (dirtyRegions.contains(candidate.getKey()) || regionHasDirtyEntry(candidate.getValue())) continue;
            for (MicrovoxelKey key : candidate.getValue().keys) volumes.remove(key);
            iterator.remove();
        }
    }

    private boolean regionHasDirtyEntry(RegionData region) {
        for (MicrovoxelKey key : region.keys) {
            if (dirty.containsKey(key)) return true;
        }
        return false;
    }

    private void unloadTransientRegion(RegionKey key, RegionData data) {
        for (MicrovoxelKey volumeKey : data.keys) volumes.remove(volumeKey);
        loadedRegions.remove(key);
    }

    /** Regions awaiting a region-file write; drives incremental compaction. */
    public synchronized int dirtyRegionCount() {
        return dirtyRegions.size();
    }

    private void flushDirtyRegions() throws IOException {
        flushDirtyRegions(Integer.MAX_VALUE);
    }

    /**
     * Writes at most {@code maxRegions} dirty regions. Compaction on huge builds is sliced
     * across worker runs (and server ticks via rescheduling) instead of freezing on one giant
     * flush. Deterministic order keeps repeated runs byte-identical for a static store.
     *
     * @return how many regions were actually written.
     */
    int flushDirtyRegions(int maxRegions) throws IOException {
        List<RegionKey> pending = new ArrayList<>(dirtyRegions);
        pending.sort(Comparator.comparing((RegionKey key) -> key.worldId.toString())
                .thenComparingInt(RegionKey::x).thenComparingInt(RegionKey::z));
        int written = 0;
        for (RegionKey key : pending) {
            if (written >= maxRegions) break;
            RegionData data = ensureRegionLoaded(key);
            if (data.keys.isEmpty()) {
                Files.deleteIfExists(regionFile(key));
                Files.deleteIfExists(regionBackupFile(key));
                Files.deleteIfExists(regionIndexFile(key));
                regions.remove(key);
                loadedRegions.remove(key);
            } else {
                writeRegion(key, data);
            }
            dirtyRegions.remove(key);
            written++;
        }
        return written;
    }

    private void writeRegion(RegionKey key, RegionData data) throws IOException {
        Files.createDirectories(regionDirectory);
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream body = new DataOutputStream(bodyBytes)) {
            body.writeLong(key.worldId.getMostSignificantBits());
            body.writeLong(key.worldId.getLeastSignificantBits());
            body.writeInt(key.x);
            body.writeInt(key.z);
            body.writeInt(data.keys.size());
            List<MicrovoxelKey> sortedKeys = data.keys.stream()
                    .sorted(Comparator.comparingInt(MicrovoxelKey::x)
                            .thenComparingInt(MicrovoxelKey::z)
                            .thenComparingInt(MicrovoxelKey::y))
                    .toList();
            for (MicrovoxelKey volumeKey : sortedKeys) {
                body.writeInt(volumeKey.x());
                body.writeInt(volumeKey.y());
                body.writeInt(volumeKey.z());
                MicrovoxelVolume volume = volumes.get(volumeKey);
                if (volume == null) throw new IOException("Loaded region lost " + volumeKey);
                writeVolume(body, volume);
            }
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        Path target = regionFile(key);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeInt(REGION_MAGIC);
            output.writeInt(REGION_VERSION);
            output.writeInt(body.length);
            output.write(body);
            output.writeInt((int) crc.getValue());
        }
        if (Files.isRegularFile(target)) {
            Files.copy(target, regionBackupFile(key), StandardCopyOption.REPLACE_EXISTING);
        }
        atomicReplace(temporary, target);
        writeRegionIndex(key, data.meta);
    }

    private RegionData readRegionWithBackup(RegionKey key) throws IOException {
        Path primary = regionFile(key);
        try {
            return readRegion(primary, key);
        } catch (IOException | RuntimeException primaryFailure) {
            Path backup = regionBackupFile(key);
            if (!Files.isRegularFile(backup)) throw primaryFailure;
            try {
                loadedFromBackup = true;
                return readRegion(backup, key);
            } catch (IOException | RuntimeException backupFailure) {
                primaryFailure.addSuppressed(backupFailure);
                throw primaryFailure;
            }
        }
    }

    private RegionData readRegion(Path source, RegionKey expected) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(source)))) {
            if (input.readInt() != REGION_MAGIC || input.readInt() != REGION_VERSION) {
                throw new IOException("Unsupported microvoxel region format: " + source);
            }
            int length = input.readInt();
            if (length < 32 || length > MAX_JOURNAL_BATCH_BYTES * 16) {
                throw new IOException("Invalid microvoxel region length: " + length);
            }
            byte[] body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Truncated microvoxel region");
            int expectedCrc = input.readInt();
            if (input.read() != -1) throw new IOException("Trailing microvoxel region bytes");
            CRC32 crc = new CRC32();
            crc.update(body);
            if ((int) crc.getValue() != expectedCrc) throw new IOException("Microvoxel region CRC mismatch");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(body))) {
                RegionKey actual = new RegionKey(new UUID(data.readLong(), data.readLong()),
                        data.readInt(), data.readInt());
                if (!actual.equals(expected)) throw new IOException("Microvoxel region identity mismatch");
                int count = data.readInt();
                if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid region entry count");
                RegionMeta meta = regions.getOrDefault(expected, new RegionMeta(0));
                RegionData result = new RegionData(meta);
                Map<ChunkKey, Integer> actualCounts = new HashMap<>();
                for (int index = 0; index < count; index++) {
                    MicrovoxelKey volumeKey = new MicrovoxelKey(expected.worldId,
                            data.readInt(), data.readInt(), data.readInt());
                    if (!RegionKey.of(volumeKey).equals(expected) || !result.keys.add(volumeKey)) {
                        throw new IOException("Invalid or duplicate key in microvoxel region");
                    }
                    volumes.put(volumeKey, readVolume(data));
                    actualCounts.merge(ChunkKey.of(volumeKey), 1, Integer::sum);
                }
                if (data.read() != -1) throw new IOException("Trailing region payload bytes");
                meta.entryCount = count;
                meta.chunkCounts.clear();
                meta.chunkCounts.putAll(actualCounts);
                regions.put(expected, meta);
                return result;
            }
        }
    }

    private void writeRegionIndex(RegionKey key, RegionMeta meta) throws IOException {
        Path target = regionIndexFile(key);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream body = new DataOutputStream(bodyBytes)) {
            body.writeLong(key.worldId.getMostSignificantBits());
            body.writeLong(key.worldId.getLeastSignificantBits());
            body.writeInt(key.x);
            body.writeInt(key.z);
            body.writeInt(meta.entryCount);
            body.writeInt(meta.chunkCounts.size());
            for (Map.Entry<ChunkKey, Integer> entry : meta.chunkCounts.entrySet()) {
                body.writeInt(entry.getKey().x());
                body.writeInt(entry.getKey().z());
                body.writeInt(entry.getValue());
            }
        }
        writeCrcEnvelope(temporary, bodyBytes.toByteArray());
        atomicReplace(temporary, target);
    }

    private RegionMeta readRegionIndex(RegionKey key) throws IOException {
        Path index = regionIndexFile(key);
        byte[] body = readCrcEnvelope(index);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            RegionKey actual = new RegionKey(new UUID(input.readLong(), input.readLong()),
                    input.readInt(), input.readInt());
            if (!actual.equals(key)) throw new IOException("Microvoxel region index identity mismatch");
            int count = input.readInt();
            int chunkCount = input.readInt();
            if (count < 0 || count > MAX_ENTRIES || chunkCount < 0
                    || chunkCount > REGION_CHUNKS * REGION_CHUNKS) {
                throw new IOException("Invalid microvoxel region index");
            }
            RegionMeta meta = new RegionMeta(count);
            int sum = 0;
            for (int i = 0; i < chunkCount; i++) {
                ChunkKey chunk = new ChunkKey(key.worldId, input.readInt(), input.readInt());
                int chunkEntries = input.readInt();
                if (chunkEntries < 1) throw new IOException("Invalid indexed chunk count");
                meta.chunkCounts.put(chunk, chunkEntries);
                sum += chunkEntries;
            }
            if (sum != count || input.read() != -1) throw new IOException("Inconsistent region index");
            return meta;
        }
    }

    private static void writeCrcEnvelope(Path target, byte[] body) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(body);
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target)))) {
            output.writeInt(REGION_MAGIC);
            output.writeInt(REGION_VERSION);
            output.writeInt(body.length);
            output.write(body);
            output.writeInt((int) crc.getValue());
        }
    }

    private static byte[] readCrcEnvelope(Path source) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(source)))) {
            if (input.readInt() != REGION_MAGIC || input.readInt() != REGION_VERSION) {
                throw new IOException("Unsupported region index format");
            }
            int length = input.readInt();
            if (length < 28 || length > 1024 * 1024) throw new IOException("Invalid region index length");
            byte[] body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Truncated region index");
            int expected = input.readInt();
            CRC32 crc = new CRC32();
            crc.update(body);
            if ((int) crc.getValue() != expected || input.read() != -1) {
                throw new IOException("Region index CRC mismatch");
            }
            return body;
        }
    }

    private Path regionFile(RegionKey key) {
        return regionDirectory.resolve(key.worldId + "_" + key.x + "_" + key.z + ".mvr");
    }

    private Path regionBackupFile(RegionKey key) {
        Path primary = regionFile(key);
        return primary.resolveSibling(primary.getFileName() + ".bak");
    }

    private Path regionIndexFile(RegionKey key) {
        return regionDirectory.resolve(key.worldId + "_" + key.x + "_" + key.z + ".idx");
    }

    private static RegionKey parseRegionFileName(String name) {
        if (!name.endsWith(".mvr")) return null;
        String[] parts = name.substring(0, name.length() - 4).split("_");
        if (parts.length != 3) return null;
        try {
            return new RegionKey(UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void atomicReplace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private Path journalFile() {
        return file.resolveSibling(file.getFileName() + ".journal");
    }

    private byte[] encodeJournalBatch(DirtyBatch batch) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(batch.entries().size());
            for (DirtyEntry entry : batch.entries()) {
                output.writeLong(entry.sequence());
                writeKey(output, entry.key());
                output.writeBoolean(entry.volume() != null);
                if (entry.volume() != null) writeVolume(output, entry.volume());
            }
        }
        if (bytes.size() > MAX_JOURNAL_BATCH_BYTES) {
            throw new IOException("Microvoxel journal batch is too large: " + bytes.size());
        }
        return bytes.toByteArray();
    }

    private void replayJournal() throws IOException {
        Path journal = journalFile();
        if (!Files.isRegularFile(journal)) return;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal)))) {
            if (input.readInt() != JOURNAL_MAGIC || input.readInt() != JOURNAL_VERSION) {
                recoveredJournalTail = true;
                return;
            }
            while (true) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException complete) {
                    return;
                }
                if (length < 1 || length > MAX_JOURNAL_BATCH_BYTES) {
                    recoveredJournalTail = true;
                    return;
                }
                byte[] body = input.readNBytes(length);
                if (body.length != length) {
                    recoveredJournalTail = true;
                    return;
                }
                int expectedCrc;
                try {
                    expectedCrc = input.readInt();
                } catch (EOFException truncated) {
                    recoveredJournalTail = true;
                    return;
                }
                CRC32 crc = new CRC32();
                crc.update(body);
                if ((int) crc.getValue() != expectedCrc) {
                    recoveredJournalTail = true;
                    return;
                }
                try {
                    replayJournalBatch(body);
                    MicrovoxelMetrics.inc("store.journal.replayed");
                } catch (IOException corruptBatch) {
                    // One corrupt inner batch truncates the tail instead of discarding the
                    // whole store, so a single bad byte can never wipe every volume.
                    MicrovoxelMetrics.inc("store.journal.dropped");
                    recoveredJournalTail = true;
                    return;
                }
            }
        }
    }

    private void replayJournalBatch(byte[] body) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid journal entry count");
            for (int index = 0; index < count; index++) {
                long sequence = input.readLong();
                MicrovoxelKey key = readKey(input);
                if (input.readBoolean()) {
                    put(key, readVolume(input));
                } else {
                    remove(key);
                }
                changeSequence = Math.max(changeSequence, sequence);
            }
            if (input.read() != -1) throw new IOException("Trailing journal batch bytes");
        }
    }

    private static void writeKey(DataOutputStream output, MicrovoxelKey key) throws IOException {
        output.writeLong(key.worldId().getMostSignificantBits());
        output.writeLong(key.worldId().getLeastSignificantBits());
        output.writeInt(key.x());
        output.writeInt(key.y());
        output.writeInt(key.z());
    }

    private static MicrovoxelKey readKey(DataInputStream input) throws IOException {
        return new MicrovoxelKey(new UUID(input.readLong(), input.readLong()),
                input.readInt(), input.readInt(), input.readInt());
    }

    private static void writeVolume(DataOutputStream output, MicrovoxelVolume volume) throws IOException {
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

    private static MicrovoxelVolume readVolume(DataInputStream input) throws IOException {
        int revision = input.readInt();
        int paletteSize = input.readUnsignedByte();
        if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
            throw new IOException("Invalid journal palette size " + paletteSize);
        }
        List<String> palette = new ArrayList<>(paletteSize);
        for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
            int length = input.readUnsignedShort();
            byte[] utf8 = input.readNBytes(length);
            if (utf8.length != length) throw new EOFException("Truncated journal palette entry");
            palette.add(new String(utf8, java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] cells = input.readNBytes(MicrovoxelVolume.CELL_COUNT);
        if (cells.length != MicrovoxelVolume.CELL_COUNT) throw new EOFException("Truncated journal volume");
        return MicrovoxelVolume.restore(revision, palette, cells);
    }
}
