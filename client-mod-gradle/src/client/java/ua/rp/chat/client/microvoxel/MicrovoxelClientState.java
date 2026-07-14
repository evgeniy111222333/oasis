package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MicrovoxelClientState {
    private static final int CLEAR = 1;
    private static final int UPSERT = 2;
    private static final int REMOVE = 3;
    private static final int MESSAGE = 4;
    private static final long MESH_BUDGET_NANOS = 3_000_000L;
    private static final Map<BlockPos, CachedVolume> VOLUMES = new HashMap<>();
    private static final Map<Long, Set<BlockPos>> CHUNKS = new HashMap<>();
    private static final Set<BlockPos> MESH_QUEUE = new LinkedHashSet<>();
    private static ClientLevel activeLevel;

    private MicrovoxelClientState() {
    }

    public static void clientTick(Minecraft minecraft) {
        if (minecraft.level != activeLevel) {
            activeLevel = minecraft.level;
            clearVolumes();
            MicrovoxelClientRenderer.clearMaterialCache();
        }
        rebuildQueuedMeshes();
    }

    public static void handle(MicrovoxelSyncPayload payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload.data()))) {
            int type = input.readUnsignedByte();
            if (type == CLEAR) {
                clearVolumes();
                return;
            }
            if (type == REMOVE) {
                BlockPos position = readPosition(input);
                if (VOLUMES.remove(position) != null) removeFromChunk(position);
                queueRebuild(position);
                return;
            }
            if (type == MESSAGE) {
                String message = readUtf8(input);
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.gui.setOverlayMessage(Component.literal(message), false);
                return;
            }
            if (type != UPSERT) return;
            BlockPos position = readPosition(input);
            int revision = input.readInt();
            int paletteSize = input.readUnsignedByte();
            if (paletteSize < 1 || paletteSize > 32) throw new IOException("Invalid palette size");
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) palette.add(readUtf8(input));
            byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
            int encoding = input.readUnsignedByte();
            if (encoding == 0) {
                if (input.readNBytes(cells, 0, cells.length) != cells.length) throw new EOFException("Truncated cells");
            } else if (encoding == 1) {
                int runs = input.readUnsignedShort();
                int cursor = 0;
                for (int run = 0; run < runs; run++) {
                    int length = input.readUnsignedShort();
                    byte material = input.readByte();
                    if (length < 1 || cursor + length > cells.length) throw new IOException("Invalid RLE run");
                    java.util.Arrays.fill(cells, cursor, cursor + length, material);
                    cursor += length;
                }
                if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
            } else {
                throw new IOException("Unknown cell encoding");
            }
            if (input.available() != 0) throw new IOException("Trailing microvoxel payload bytes");
            MicrovoxelVolume volume = new MicrovoxelVolume(revision, palette, cells);
            BlockPos immutable = position.immutable();
            if (!VOLUMES.containsKey(immutable)) addToChunk(immutable);
            VOLUMES.put(immutable, new CachedVolume(volume));
            queueRebuild(immutable);
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Rejected sync payload: " + error.getMessage());
        }
    }

    public static CachedVolume get(BlockPos position) {
        return VOLUMES.get(position);
    }

    public static VoxelShape collisionShape(BlockPos position) {
        CachedVolume cached = VOLUMES.get(position);
        return cached == null ? null : cached.shape;
    }

    public static Collection<Map.Entry<BlockPos, CachedVolume>> volumesNear(
            double blockX, double blockZ, double radius) {
        int minChunkX = floorChunk(blockX - radius);
        int maxChunkX = floorChunk(blockX + radius);
        int minChunkZ = floorChunk(blockZ - radius);
        int maxChunkZ = floorChunk(blockZ + radius);
        List<Map.Entry<BlockPos, CachedVolume>> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (BlockPos position : CHUNKS.getOrDefault(chunkKey(chunkX, chunkZ), Set.of())) {
                    CachedVolume volume = VOLUMES.get(position);
                    if (volume != null) result.add(Map.entry(position, volume));
                }
            }
        }
        return result;
    }

    public static List<MicrovoxelRaycaster.Entry> raycastEntries(double x, double y, double z, double radius) {
        double radiusSquared = radius * radius;
        List<MicrovoxelRaycaster.Entry> result = new ArrayList<>();
        for (Map.Entry<BlockPos, CachedVolume> entry : volumesNear(x, z, radius)) {
            BlockPos position = entry.getKey();
            double dx = position.getX() + 0.5 - x;
            double dy = position.getY() + 0.5 - y;
            double dz = position.getZ() + 0.5 - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                result.add(new MicrovoxelRaycaster.Entry(
                        position.getX(), position.getY(), position.getZ(), entry.getValue().volume));
            }
        }
        return result;
    }

    private static void rebuild(BlockPos position) {
        CachedVolume cached = VOLUMES.get(position);
        if (cached == null) return;
        cached.mesh = MicrovoxelGreedyMesher.build(cached.volume, (x, y, z) -> materialAt(position, x, y, z));
    }

    private static int materialAt(BlockPos base, int x, int y, int z) {
        int offsetX = Math.floorDiv(x, 16);
        int offsetY = Math.floorDiv(y, 16);
        int offsetZ = Math.floorDiv(z, 16);
        CachedVolume cached = VOLUMES.get(base.offset(offsetX, offsetY, offsetZ));
        if (cached == null) return 0;
        return cached.volume.materialAt(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16));
    }

    private static void queueRebuild(BlockPos position) {
        MESH_QUEUE.add(position.immutable());
        MESH_QUEUE.add(position.offset(1, 0, 0));
        MESH_QUEUE.add(position.offset(-1, 0, 0));
        MESH_QUEUE.add(position.offset(0, 1, 0));
        MESH_QUEUE.add(position.offset(0, -1, 0));
        MESH_QUEUE.add(position.offset(0, 0, 1));
        MESH_QUEUE.add(position.offset(0, 0, -1));
    }

    private static void rebuildQueuedMeshes() {
        long deadline = System.nanoTime() + MESH_BUDGET_NANOS;
        Iterator<BlockPos> iterator = MESH_QUEUE.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && (rebuilt == 0 || System.nanoTime() < deadline)) {
            BlockPos position = iterator.next();
            iterator.remove();
            rebuild(position);
            rebuilt++;
        }
    }

    private static void clearVolumes() {
        VOLUMES.clear();
        CHUNKS.clear();
        MESH_QUEUE.clear();
    }

    private static void addToChunk(BlockPos position) {
        CHUNKS.computeIfAbsent(chunkKey(position.getX() >> 4, position.getZ() >> 4), ignored -> new HashSet<>())
                .add(position);
    }

    private static void removeFromChunk(BlockPos position) {
        long key = chunkKey(position.getX() >> 4, position.getZ() >> 4);
        Set<BlockPos> indexed = CHUNKS.get(key);
        if (indexed == null) return;
        indexed.remove(position);
        if (indexed.isEmpty()) CHUNKS.remove(key);
    }

    private static int floorChunk(double blockCoordinate) {
        return ((int) Math.floor(blockCoordinate)) >> 4;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static VoxelShape buildShape(MicrovoxelVolume volume) {
        List<MicrovoxelVolume.Cuboid> cuboids = volume.collisionCuboids();
        VoxelShape[] parts = new VoxelShape[cuboids.size()];
        for (int index = 0; index < cuboids.size(); index++) {
            MicrovoxelVolume.Cuboid cuboid = cuboids.get(index);
            parts[index] = Shapes.box(
                    cuboid.minX() / 16.0, cuboid.minY() / 16.0, cuboid.minZ() / 16.0,
                    cuboid.maxX() / 16.0, cuboid.maxY() / 16.0, cuboid.maxZ() / 16.0);
        }
        return Shapes.or(Shapes.empty(), parts).optimize();
    }

    private static BlockPos readPosition(DataInputStream input) throws IOException {
        return new BlockPos(input.readInt(), input.readInt(), input.readInt());
    }

    private static String readUtf8(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated UTF-8 value");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static final class CachedVolume {
        public final MicrovoxelVolume volume;
        public final VoxelShape shape;
        public volatile List<MicrovoxelGreedyMesher.Face> mesh = List.of();

        private CachedVolume(MicrovoxelVolume volume) {
            this.volume = volume;
            this.shape = buildShape(volume);
        }
    }
}
