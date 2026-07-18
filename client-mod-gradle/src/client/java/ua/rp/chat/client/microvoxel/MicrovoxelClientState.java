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
    private static final long CHUNK_BATCH_BUDGET_NANOS = 1_500_000L;
    private static final Map<BlockPos, CachedVolume> VOLUMES = new HashMap<>();
    private static final Map<Long, Set<BlockPos>> CHUNKS = new HashMap<>();
    private static final Set<BlockPos> MESH_QUEUE = new LinkedHashSet<>();
    /**
     * A block-state packet and the matching microvoxel sync packet can arrive in either order.
     * Rebuilding once immediately is normally enough; rebuilding the same local boundary two
     * client ticks later makes the result deterministic without polling every volume every tick.
     */
    private static final Map<BlockPos, Integer> DELAYED_BOUNDARY_REBUILDS = new HashMap<>();
    /**
     * Rendering never walks the global volume map.  A batch is a stable, flattened snapshot of
     * one chunk's greedy faces and is rebuilt only when a volume in that chunk changes.  This is
     * deliberately CPU-side for now: it gives us deterministic dirty-region work and keeps the
     * renderer ready for a later VBO backend without making edit latency depend on GPU uploads.
     */
    private static final Map<Long, ChunkBatch> CHUNK_BATCHES = new HashMap<>();
    private static final Set<Long> CHUNK_BATCH_QUEUE = new LinkedHashSet<>();
    /**
     * One-shot runtime evidence for the microvoxel pipeline.  This is intentionally keyed by
     * volume revision: a diagnostic session stays compact while still proving which shape and
     * material were used for every edited state the player actually sees.
     */
    private static final Set<String> PROBE_EMITTED = new HashSet<>();
    private static ClientLevel activeLevel;
    private static int clientTick;

    private MicrovoxelClientState() {
    }

    public static void clientTick(Minecraft minecraft) {
        clientTick++;
        if (minecraft.level != activeLevel) {
            activeLevel = minecraft.level;
            clearVolumes();
            MicrovoxelClientRenderer.clearMaterialCache();
        }
        processDelayedBoundaryRebuilds();
        rebuildQueuedMeshes();
        rebuildQueuedChunkBatches();
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
                queueChunkBatch(position);
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
            long tStart = System.nanoTime();
            BlockPos position = readPosition(input);
            int revision = input.readInt();
            int paletteSize = input.readUnsignedByte();
            if (paletteSize < 1 || paletteSize > 32) throw new IOException("Invalid palette size");
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) palette.add(readUtf8(input));
            byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
            int encoding = input.readUnsignedByte();
            long tDecodeStart = System.nanoTime();
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
            long tDecodeEnd = System.nanoTime();
            if (input.available() != 0) throw new IOException("Trailing microvoxel payload bytes");
            MicrovoxelVolume volume = new MicrovoxelVolume(revision, palette, cells);
            BlockPos immutable = position.immutable();
            if (!VOLUMES.containsKey(immutable)) addToChunk(immutable);
            
            CachedVolume oldCached = VOLUMES.get(immutable);
            VoxelShape fallback = (oldCached != null) ? oldCached.getShape() : null;
            CachedVolume cachedVolume = new CachedVolume(immutable, volume, fallback);
            if (oldCached != null) {
                cachedVolume.mesh = oldCached.mesh;
            }
            VOLUMES.put(immutable, cachedVolume);
            
            long tRebuildStart = System.nanoTime();
            rebuild(immutable);
            long tRebuildEnd = System.nanoTime();
            
            long tQueueStart = System.nanoTime();
            boolean touchBoundary = (oldCached == null || changesTouchBoundary(oldCached.volume, volume));
            if (touchBoundary) {
                queueNeighborsRebuild(immutable);
                scheduleBoundaryRebuild(immutable);
            }
            long tQueueEnd = System.nanoTime();
            
            long tTotal = System.nanoTime() - tStart;
            double decodeUs = (tDecodeEnd - tDecodeStart) / 1000.0;
            double rebuildUs = (tRebuildEnd - tRebuildStart) / 1000.0;
            double queueUs = (tQueueEnd - tQueueStart) / 1000.0;
            double totalUs = tTotal / 1000.0;
            
            EclipseClientMod.LOGGER.info("[MICROVOXEL-PERF] SYNC_UPSERT pos={} | Total: {}us (Decode: {}us, Rebuild: {}us, Queue: {}us) | Faces: {} | BoundaryTouch: {}",
                    immutable.toShortString(),
                    String.format("%.2f", totalUs),
                    String.format("%.2f", decodeUs),
                    String.format("%.2f", rebuildUs),
                    String.format("%.2f", queueUs),
                    cachedVolume.mesh.size(),
                    touchBoundary
            );
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Rejected sync payload: " + error.getMessage());
        }
    }

    public static CachedVolume get(BlockPos position) {
        return VOLUMES.get(position);
    }

    public static VoxelShape collisionShape(BlockPos position) {
        CachedVolume cached = VOLUMES.get(position);
        return cached == null ? null : cached.getShape();
    }

    public static void probeShape(String hook, BlockPos position, VoxelShape shape) {
        CachedVolume cached = VOLUMES.get(position);
        if (cached == null) return;
        String key = "shape:" + hook + ':' + position.asLong() + ':' + cached.volume.revision();
        if (!PROBE_EMITTED.add(key)) return;
        List<MicrovoxelVolume.Cuboid> cuboids = cached.volume.collisionCuboids();
        EclipseClientMod.LOGGER.info("[MICROVOXEL-PROBE] SHAPE hook={} pos={} revision={} cuboids={} aabbs={} bounds={}",
                hook, position.toShortString(), cached.volume.revision(), cuboids.size(), shape.toAabbs().size(),
                shape.isEmpty() ? "empty" : shape.bounds());
    }

    public static void probeRender(BlockPos position, MicrovoxelGreedyMesher.Face face,
                                   String material, int layerCount, String renderType, boolean translucent,
                                   float minU, float maxU, float minV, float maxV) {
        CachedVolume cached = VOLUMES.get(position);
        if (cached == null) return;
        String key = "render:" + position.asLong() + ':' + cached.volume.revision();
        if (!PROBE_EMITTED.add(key)) return;
        EclipseClientMod.LOGGER.info(
                "[MICROVOXEL-PROBE] RENDER pos={} revision={} material={} face={} layers={} type={} translucent={} uv=[{},{}]x[{},{}]",
                position.toShortString(), cached.volume.revision(), material, face.direction(), layerCount,
                renderType, translucent, minU, maxU, minV, maxV);
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

    /** Returns immutable chunk-level render batches overlapping the requested horizontal radius. */
    public static Collection<ChunkBatch> batchesNear(double blockX, double blockZ, double radius) {
        int minChunkX = floorChunk(blockX - radius);
        int maxChunkX = floorChunk(blockX + radius);
        int minChunkZ = floorChunk(blockZ - radius);
        int maxChunkZ = floorChunk(blockZ + radius);
        List<ChunkBatch> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkBatch batch = CHUNK_BATCHES.get(chunkKey(chunkX, chunkZ));
                if (batch != null && !batch.faces.isEmpty()) result.add(batch);
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
        CachedVolume center = VOLUMES.get(position);
        if (center == null) return;

        CachedVolume downVol = VOLUMES.get(position.below());
        CachedVolume upVol = VOLUMES.get(position.above());
        CachedVolume northVol = VOLUMES.get(position.north());
        CachedVolume southVol = VOLUMES.get(position.south());
        CachedVolume westVol = VOLUMES.get(position.west());
        CachedVolume eastVol = VOLUMES.get(position.east());

        boolean downSolid = (downVol == null && activeLevel != null) && activeLevel.getBlockState(position.below()).isSolidRender();
        boolean upSolid = (upVol == null && activeLevel != null) && activeLevel.getBlockState(position.above()).isSolidRender();
        boolean northSolid = (northVol == null && activeLevel != null) && activeLevel.getBlockState(position.north()).isSolidRender();
        boolean southSolid = (southVol == null && activeLevel != null) && activeLevel.getBlockState(position.south()).isSolidRender();
        boolean westSolid = (westVol == null && activeLevel != null) && activeLevel.getBlockState(position.west()).isSolidRender();
        boolean eastSolid = (eastVol == null && activeLevel != null) && activeLevel.getBlockState(position.east()).isSolidRender();

        center.mesh = MicrovoxelGreedyMesher.build(center.volume, (x, y, z) -> {
            if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
                return center.volume.materialAt(x, y, z);
            }
            int ox = Math.floorDiv(x, 16);
            int oy = Math.floorDiv(y, 16);
            int oz = Math.floorDiv(z, 16);
            CachedVolume neighbourVol = null;
            boolean neighbourSolid = false;
            if (ox == -1) {
                neighbourVol = westVol;
                neighbourSolid = westSolid;
            } else if (ox == 1) {
                neighbourVol = eastVol;
                neighbourSolid = eastSolid;
            } else if (oy == -1) {
                neighbourVol = downVol;
                neighbourSolid = downSolid;
            } else if (oy == 1) {
                neighbourVol = upVol;
                neighbourSolid = upSolid;
            } else if (oz == -1) {
                neighbourVol = northVol;
                neighbourSolid = northSolid;
            } else if (oz == 1) {
                neighbourVol = southVol;
                neighbourSolid = southSolid;
            }
            if (neighbourVol != null) {
                return neighbourVol.volume.materialAt(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16));
            }
            return neighbourSolid ? 1 : 0;
        });
        queueChunkBatch(position);
    }

    /** Used by the renderer's vertex AO sampler. Coordinates are local to {@code base}. */
    public static boolean solidAt(BlockPos base, int x, int y, int z) {
        return materialAt(base, x, y, z) != 0;
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static int materialAt(BlockPos base, int x, int y, int z) {
        int offsetX = Math.floorDiv(x, 16);
        int offsetY = Math.floorDiv(y, 16);
        int offsetZ = Math.floorDiv(z, 16);
        if (offsetX == 0 && offsetY == 0 && offsetZ == 0) {
            CachedVolume cached = VOLUMES.get(base);
            return cached != null ? cached.volume.materialAt(x, y, z) : 0;
        }
        BlockPos.MutableBlockPos scratch = SCRATCH_POS.get();
        scratch.set(base.getX() + offsetX, base.getY() + offsetY, base.getZ() + offsetZ);
        CachedVolume cached = VOLUMES.get(scratch);
        return cached != null ? cached.volume.materialAt(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16)) : 0;
    }

    private static boolean changesTouchBoundary(MicrovoxelVolume oldVolume, MicrovoxelVolume newVolume) {
        if (oldVolume == null) return true;
        byte[] oldCells = oldVolume.cellsCopy();
        byte[] newCells = newVolume.cellsCopy();
        for (int i = 0; i < oldCells.length; i++) {
            if (oldCells[i] != newCells[i]) {
                int cx = MicrovoxelVolume.x(i);
                int cy = MicrovoxelVolume.y(i);
                int cz = MicrovoxelVolume.z(i);
                if (cx == 0 || cx == 15 || cy == 0 || cy == 15 || cz == 0 || cz == 15) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void queueRebuild(BlockPos position) {
        MESH_QUEUE.add(position.immutable());
        queueNeighborsRebuild(position);
    }

    private static void queueNeighborsRebuild(BlockPos position) {
        MESH_QUEUE.add(position.offset(1, 0, 0));
        MESH_QUEUE.add(position.offset(-1, 0, 0));
        MESH_QUEUE.add(position.offset(0, 1, 0));
        MESH_QUEUE.add(position.offset(0, -1, 0));
        MESH_QUEUE.add(position.offset(0, 0, 1));
        MESH_QUEUE.add(position.offset(0, 0, -1));
    }

    private static void scheduleBoundaryRebuild(BlockPos position) {
        DELAYED_BOUNDARY_REBUILDS.put(position.immutable(), clientTick + 2);
    }

    private static void processDelayedBoundaryRebuilds() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator = DELAYED_BOUNDARY_REBUILDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() > clientTick) continue;
            queueRebuild(entry.getKey());
            iterator.remove();
        }
    }

    private static void queueChunkBatch(BlockPos position) {
        CHUNK_BATCH_QUEUE.add(chunkKey(position.getX() >> 4, position.getZ() >> 4));
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

    private static void rebuildQueuedChunkBatches() {
        long deadline = System.nanoTime() + CHUNK_BATCH_BUDGET_NANOS;
        Iterator<Long> iterator = CHUNK_BATCH_QUEUE.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && (rebuilt == 0 || System.nanoTime() < deadline)) {
            long key = iterator.next();
            iterator.remove();
            Set<BlockPos> positions = CHUNKS.get(key);
            if (positions == null || positions.isEmpty()) {
                CHUNK_BATCHES.remove(key);
                rebuilt++;
                continue;
            }
            List<ChunkFace> faces = new ArrayList<>();
            for (BlockPos position : positions) {
                CachedVolume cached = VOLUMES.get(position);
                if (cached == null) continue;
                for (MicrovoxelGreedyMesher.Face face : cached.mesh) {
                    faces.add(new ChunkFace(position, cached, face));
                }
            }
            CHUNK_BATCHES.put(key, new ChunkBatch(faces));
            rebuilt++;
        }
    }

    private static void clearVolumes() {
        VOLUMES.clear();
        CHUNKS.clear();
        MESH_QUEUE.clear();
        DELAYED_BOUNDARY_REBUILDS.clear();
        CHUNK_BATCHES.clear();
        CHUNK_BATCH_QUEUE.clear();
        PROBE_EMITTED.clear();
    }

    private static void addToChunk(BlockPos position) {
        CHUNKS.computeIfAbsent(chunkKey(position.getX() >> 4, position.getZ() >> 4), ignored -> new HashSet<>())
                .add(position);
        queueChunkBatch(position);
    }

    private static void removeFromChunk(BlockPos position) {
        long key = chunkKey(position.getX() >> 4, position.getZ() >> 4);
        Set<BlockPos> indexed = CHUNKS.get(key);
        if (indexed == null) return;
        indexed.remove(position);
        if (indexed.isEmpty()) CHUNKS.remove(key);
        CHUNK_BATCH_QUEUE.add(key);
    }

    private static int floorChunk(double blockCoordinate) {
        return ((int) Math.floor(blockCoordinate)) >> 4;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static VoxelShape combineShapes(VoxelShape[] shapes, int start, int end) {
        if (start >= end) return Shapes.empty();
        if (start == end - 1) return shapes[start];
        int mid = (start + end) / 2;
        return Shapes.or(combineShapes(shapes, start, mid), combineShapes(shapes, mid, end));
    }

    private static VoxelShape buildShape(MicrovoxelVolume volume) {
        List<MicrovoxelVolume.Cuboid> cuboids = volume.collisionCuboids();
        if (cuboids.isEmpty()) return Shapes.empty();
        VoxelShape[] parts = new VoxelShape[cuboids.size()];
        for (int index = 0; index < cuboids.size(); index++) {
            MicrovoxelVolume.Cuboid cuboid = cuboids.get(index);
            parts[index] = Shapes.box(
                    cuboid.minX() / 16.0, cuboid.minY() / 16.0, cuboid.minZ() / 16.0,
                    cuboid.maxX() / 16.0, cuboid.maxY() / 16.0, cuboid.maxZ() / 16.0);
        }
        return combineShapes(parts, 0, parts.length).optimize();
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

    private static final java.util.concurrent.ExecutorService SHAPE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Microvoxel-Shape-Generator");
                thread.setDaemon(true);
                return thread;
            });

    public static net.minecraft.world.level.block.state.BlockState getBaseBlockState(BlockPos pos) {
        CachedVolume cached = VOLUMES.get(pos);
        if (cached == null) return null;
        return cached.getBaseBlockState();
    }

    public static final class CachedVolume {
        public final BlockPos position;
        public final MicrovoxelVolume volume;
        private final VoxelShape fallback;
        private volatile VoxelShape shape;
        private volatile boolean shapeGenerating = false;
        public volatile List<MicrovoxelGreedyMesher.Face> mesh = List.of();
        private net.minecraft.world.level.block.state.BlockState baseBlockState;

        private CachedVolume(BlockPos position, MicrovoxelVolume volume, VoxelShape fallback) {
            this.position = position.immutable();
            this.volume = volume;
            this.fallback = fallback;
        }

        public net.minecraft.world.level.block.state.BlockState getBaseBlockState() {
            if (baseBlockState == null) {
                String matStr = null;
                for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                    if (volume.occupied(cell)) {
                        matStr = volume.material(cell);
                        break;
                    }
                }
                if (matStr != null && !matStr.isEmpty()) {
                    baseBlockState = parseBlockState(matStr);
                } else {
                    baseBlockState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                }
            }
            return baseBlockState;
        }

        private static net.minecraft.world.level.block.state.BlockState parseBlockState(String value) {
            try {
                int propertiesStart = value.indexOf('[');
                String identifierText = propertiesStart < 0 ? value : value.substring(0, propertiesStart);
                net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                        net.minecraft.resources.Identifier.parse(identifierText));
                return block.defaultBlockState();
            } catch (RuntimeException error) {
                return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            }
        }

        public VoxelShape getShape() {
            VoxelShape s = shape;
            if (s == null) {
                triggerAsyncShapeGen();
                return fallback != null ? fallback : Shapes.block();
            }
            return s;
        }

        private synchronized void triggerAsyncShapeGen() {
            if (shapeGenerating || shape != null) return;
            shapeGenerating = true;
            SHAPE_EXECUTOR.submit(() -> {
                try {
                    long start = System.nanoTime();
                    VoxelShape newShape = buildShape(volume);
                    this.shape = newShape;
                    long durationNs = System.nanoTime() - start;
                    int cuboids = volume.collisionCuboids().size();
                    EclipseClientMod.LOGGER.info("[MICROVOXEL-PERF] ASYNC_SHAPE_GEN pos={} | Duration: {}us | Cuboids: {}",
                            position.toShortString(), String.format("%.2f", durationNs / 1000.0), cuboids);
                } catch (Throwable t) {
                    EclipseClientMod.LOGGER.error("Failed to generate async shape", t);
                } finally {
                    shapeGenerating = false;
                }
            });
        }
    }

    public static final class ChunkBatch {
        private final List<ChunkFace> faces;

        private ChunkBatch(List<ChunkFace> faces) {
            this.faces = List.copyOf(faces);
        }

        public List<ChunkFace> faces() {
            return faces;
        }
    }

    public record ChunkFace(BlockPos position, CachedVolume cached, MicrovoxelGreedyMesher.Face face) {
    }
}
