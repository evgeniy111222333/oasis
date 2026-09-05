package ua.rp.chat.client.microvoxel;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Dynamic marker model compiled by the normal terrain section compiler.
 *
 * <p>This is deliberately not an immediate-mode renderer. Once emitted, the geometry is owned by
 * the vanilla section mesh and therefore remains in its persistent GPU buffers until the section
 * is dirtied or unloaded. Network edits only enqueue bounded section rebuilds.</p>
 */
public final class MicrovoxelSectionModel extends WrapperBlockStateModel {
    /** Combined flags for volumes containing any translucent material (previous default). */
    public static final int GENERAL_MATERIAL_FLAGS =
            BakedQuad.FLAG_TRANSLUCENT | BakedQuad.FLAG_ANIMATED;
    /** Flags for fully solid volumes: the cheap opaque GPU path, no blending or sorting. */
    public static final int OPAQUE_MATERIAL_FLAGS = 0;
    private static final ThreadLocal<MaterialCache> MATERIAL_CACHE =
            ThreadLocal.withInitial(MaterialCache::new);

    private MicrovoxelSectionModel(BlockStateModel wrapped) {
        super(wrapped);
    }

    public static void register() {
        ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register(
                (model, context) -> MicrovoxelBlocks.isMarker(context.state())
                        ? new MicrovoxelSectionModel(model)
                        : model));
    }

    public static void clearThreadCaches() {
        MATERIAL_CACHE.remove();
    }

    /**
     * Sodium ships in the production modpack and replaces fluid meshing wholesale: its own
     * pipeline never calls the vanilla FluidRenderer our suppression mixin targets (verified
     * against sodium-fabric-0.8.12 bytecode: a private DefaultFluidRenderer plus a Fabric
     * Fluids-API adapter). Emitting our precise surface on top would double-render every
     * bath — so under Sodium we degrade to the vanilla full-cube visual by default (levels,
     * physics and sync stay exact; only the surface snaps to full-cube).
     *
     * <p>Escape hatch for in-game A/B testing:
     * {@code -Drpchat.microvoxel.sodiumPreciseFluid=true} re-enables the precise surface
     * under Sodium. If baths render exactly once, our FREYA model flows through Sodium's
     * pipeline and the flag can graduate to default-on; if they double-render or vanish,
     * native suppression stays impossible without a Sodium-side hook. A blind global water
     * model replacement was deliberately rejected: no headless test can validate it.
     */
    static final boolean SODIUM_PRESENT = detectSodium();

    /** Operator A/B gate for the precise fluid surface under Sodium (default off = safe). */
    static final boolean SODIUM_PRECISE_FLUID =
            Boolean.getBoolean("rpchat.microvoxel.sodiumPreciseFluid");

    private static boolean detectSodium() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Geometry source for terrain compilation. Defaults to the live client state; tests and
     * future GPU backends substitute their own provider without touching this model.
     */
    private static volatile MicrovoxelGeometryProvider geometryProvider =
            MicrovoxelClientState.geometryProvider();

    public static void setGeometryProvider(MicrovoxelGeometryProvider provider) {
        if (provider != null) geometryProvider = provider;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
                          BlockState state, RandomSource random,
                          Predicate<@Nullable Direction> cullTest) {
        MicrovoxelClientState.CachedVolume cached = MicrovoxelClientState.get(pos);
        if (cached == null) {
            MicrovoxelClientState.noteMissingVolume(pos);
            return;
        }
        List<MicrovoxelGreedyMesher.Face> mesh = geometryProvider.meshFor(pos);
        if (mesh.isEmpty()) {
            return;
        }

        MicrovoxelClientState.FluidView fluid = MicrovoxelClientState.fluidAt(pos);
        // Lava is exempt from the Sodium gate: vanilla draws nothing for lava markers (no
        // lava fluidstate exists), so there is no double-render to avoid — only invisible
        // burning pools if we skip. Water keeps the gate (vanilla full-cube fallback).
        if (fluid != null && (fluid.lava() || !SODIUM_PRESENT || SODIUM_PRECISE_FLUID)) {
            emitFluidSurface(emitter, level, pos, fluid);
        }

        List<String> palette = cached.volume.palette();
        for (MicrovoxelGreedyMesher.Face face : mesh) {
            int material = face.material();
            if (material <= 0 || material >= palette.size()) continue;
            String materialName = palette.get(material);
            Direction direction = Direction.valueOf(face.direction().name());
            MaterialFaces materialFaces = materialFaces(materialName);
            List<BakedQuad> quads = materialFaces.faces.get(direction);

            if (quads == null || quads.isEmpty()) {
                emitParticleFallback(emitter, materialFaces, face, direction);
                continue;
            }
            for (BakedQuad quad : quads) {
                emitMaterialQuad(emitter, level, pos, materialFaces.state, face, direction, quad);
            }
        }
    }

    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos,
                                               BlockState state, RandomSource random) {
        MicrovoxelClientState.CachedVolume cached = MicrovoxelClientState.get(pos);
        if (cached == null) {
            MicrovoxelClientState.noteMissingVolume(pos);
            return GeometryKey.EMPTY;
        }
        List<MicrovoxelGreedyMesher.Face> mesh = geometryProvider.meshFor(pos);
        return new GeometryKey(pos.asLong(), geometryProvider.revisionOf(pos),
                cached.volume.palette().hashCode(), mesh.size(), geometryProvider.renderFlagsFor(pos),
                geometryProvider.fluidRevisionOf(pos));
    }

    @Override
    public int materialFlags() {
        return GENERAL_MATERIAL_FLAGS;
    }

    /**
     * Per-volume render-pass selection. Fully solid volumes compile into the opaque pass
     * (cheap depth-tested GPU path); anything translucent falls back to the previous combined
     * flags. The flag participates in the geometry key above, so a palette change that flips
     * opacity rebuilds the section instead of leaking into the wrong pass.
     */
    @Override
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state,
                             RandomSource random) {
        return geometryProvider.renderFlagsFor(pos);
    }

    private static void emitMaterialQuad(QuadEmitter emitter, BlockAndTintGetter level,
                                         BlockPos pos, BlockState materialState,
                                         MicrovoxelGreedyMesher.Face face, Direction direction,
                                         BakedQuad source) {
        emitter.fromBakedQuad(source);
        setFacePositionsAndUvs(emitter, face, source);
        emitter.nominalFace(direction).cullFace(null);

        BakedQuad.MaterialInfo info = source.materialInfo();
        int color = 0xFFFFFFFF;
        if (info.isTinted()) {
            BlockTintSource tint = Minecraft.getInstance().getBlockColors()
                    .getTintSource(materialState, info.tintIndex());
            if (tint != null) {
                color = 0xFF000000 | (tint.colorInWorld(materialState, level, pos) & 0xFFFFFF);
            }
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            emitter.color(vertex, ARGB.multiply(emitter.color(vertex), color));
        }
        emitter.tintIndex(-1);
        emitter.emit();
    }

    /**
     * Precise voxel fluid surface: one top quad per wet column at its exact height plus skirts
     * down to lower neighbours. Water uses the vanilla still sprite with the vanilla water
     * tint source (biome color, resolved once per volume — the exact path FluidRenderer
     * itself uses, so swamps read green and oceans blue); lava uses its still sprite
     * untinted, exactly like vanilla. Brightness follows the section lightmap like every
     * other emitted quad.
     */
    private static void emitFluidSurface(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
                                         MicrovoxelClientState.FluidView fluid) {
        net.minecraft.client.resources.model.sprite.Material.Baked waterMaterial;
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
        int tint;
        try {
            boolean lava = fluid.lava();
            var fluidModel = net.minecraft.client.Minecraft.getInstance().getModelManager()
                    .getFluidStateModelSet().get(lava
                            ? net.minecraft.world.level.material.Fluids.LAVA.getSource(false)
                            : net.minecraft.world.level.material.Fluids.WATER.getSource(false));
            waterMaterial = fluidModel.stillMaterial();
            sprite = waterMaterial.sprite();
            tint = lava ? 0xFFFFFFFF : 0xFF000000 | (fluidModel.tintSource().colorInWorld(
                    net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),
                    level, pos) & 0xFFFFFF);
        } catch (RuntimeException unavailable) {
            return;
        }
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                float surfaceY = 0.0f;
                for (int y = 15; y >= 0; y--) {
                    int cellLevel = fluid.level(x | (z << 4) | (y << 8));
                    if (cellLevel > 0) {
                        surfaceY = (y + cellLevel / 16.0f) / 16.0f;
                        break;
                    }
                }
                if (surfaceY <= 0.0f) continue;
                float x0 = pos.getX() + x / 16.0f;
                float x1 = pos.getX() + (x + 1) / 16.0f;
                float z0 = pos.getZ() + z / 16.0f;
                float z1 = pos.getZ() + (z + 1) / 16.0f;
                float y = pos.getY() + surfaceY;
                emitter.materialBake(waterMaterial, -1);
                positions(emitter, x0, y, z1, x1, y, z1, x1, y, z0, x0, y, z0);
                emitter.uv(0, u0, v1);
                emitter.uv(1, u1, v1);
                emitter.uv(2, u1, v0);
                emitter.uv(3, u0, v0);
                for (int vertex = 0; vertex < 4; vertex++) {
                    emitter.color(vertex, tint);
                }
                emitter.nominalFace(Direction.UP).cullFace(null).diffuseShade(true).emit();
                emitFluidSkirts(emitter, level, pos, waterMaterial, tint, u0, u1, v0, v1, x, z, surfaceY);
            }
        }
    }

    /** Side skirts from the surface down to lower neighbours (hidden against solid rock). */
    private static void emitFluidSkirts(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
                                        net.minecraft.client.resources.model.sprite.Material.Baked waterMaterial,
                                        int tint, float u0, float u1, float v0, float v1,
                                        int x, int z, float surfaceY) {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            int nx = x + side[0];
            int nz = z + side[1];
            float neighbourHeight = fluidNeighbourHeight(level, pos, nx, nz);
            if (neighbourHeight < 0.0f || neighbourHeight >= surfaceY - 1.0E-4f) continue;
            float yTop = pos.getY() + surfaceY;
            float yBottom = pos.getY() + Math.max(0.0f, neighbourHeight);
            float x0 = pos.getX() + x / 16.0f;
            float x1 = pos.getX() + (x + 1) / 16.0f;
            float z0 = pos.getZ() + z / 16.0f;
            float z1 = pos.getZ() + (z + 1) / 16.0f;
            emitter.materialBake(waterMaterial, -1);
            if (side[0] == 1) {
                positions(emitter, x1, yBottom, z1, x1, yBottom, z0, x1, yTop, z0, x1, yTop, z1);
                emitter.nominalFace(Direction.EAST);
            } else if (side[0] == -1) {
                positions(emitter, x0, yBottom, z0, x0, yBottom, z1, x0, yTop, z1, x0, yTop, z0);
                emitter.nominalFace(Direction.WEST);
            } else if (side[1] == 1) {
                positions(emitter, x0, yBottom, z1, x1, yBottom, z1, x1, yTop, z1, x0, yTop, z1);
                emitter.nominalFace(Direction.SOUTH);
            } else {
                positions(emitter, x1, yBottom, z0, x0, yBottom, z0, x0, yTop, z0, x1, yTop, z0);
                emitter.nominalFace(Direction.NORTH);
            }
            emitter.uv(0, u0, v1);
            emitter.uv(1, u1, v1);
            emitter.uv(2, u1, v0);
            emitter.uv(3, u0, v0);
            for (int vertex = 0; vertex < 4; vertex++) {
                emitter.color(vertex, tint);
            }
            emitter.cullFace(null).diffuseShade(true).emit();
        }
    }

    /**
     * Water height of the neighbouring column, or -1 when hidden (solid rock or a brimful
     * vanilla source). Marker neighbours without fluid data yet read as full to avoid seam
     * flicker during snapshots; dry markers and air read as their true height.
     */
    private static float fluidNeighbourHeight(BlockAndTintGetter level, BlockPos pos, int nx, int nz) {
        int blockX = pos.getX() + Math.floorDiv(nx, 16);
        int blockZ = pos.getZ() + Math.floorDiv(nz, 16);
        int localX = Math.floorMod(nx, 16);
        int localZ = Math.floorMod(nz, 16);
        BlockPos neighbour = new BlockPos(blockX, pos.getY(), blockZ);
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(neighbour);
        if (state.isSolidRender()) return -1.0f;
        if (MicrovoxelBlocks.isMarker(state)) {
            MicrovoxelClientState.FluidView neighbourFluid =
                    MicrovoxelClientState.fluidAt(neighbour);
            if (neighbourFluid == null) return 1.0f;
            float top = 0.0f;
            for (int y = 15; y >= 0; y--) {
                int cellLevel = neighbourFluid.level(localX | (localZ << 4) | (y << 8));
                if (cellLevel > 0) {
                    top = (y + cellLevel / 16.0f) / 16.0f;
                    break;
                }
            }
            return top;
        }
        if (!state.getFluidState().isEmpty()) return 1.0f;
        return 0.0f;
    }

    private static void emitParticleFallback(QuadEmitter emitter, MaterialFaces material,
                                             MicrovoxelGreedyMesher.Face face,
                                             Direction direction) {
        setFacePositions(emitter, face);
        emitter.nominalFace(direction).cullFace(null)
                .materialBake(material.model.particleMaterial(), 0)
                .diffuseShade(true)
                .emit();
    }

    private static void setFacePositionsAndUvs(QuadEmitter emitter,
                                               MicrovoxelGreedyMesher.Face face,
                                               BakedQuad source) {
        setFacePositions(emitter, face);
        UvPatch uv = UvPatch.from(source);
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = emitter.x(vertex);
            float y = emitter.y(vertex);
            float z = emitter.z(vertex);
            UvPoint sample = uv.sample(face.direction(), x, y, z);
            emitter.uv(vertex, sample.u, sample.v);
        }
    }

    private static void setFacePositions(QuadEmitter emitter, MicrovoxelGreedyMesher.Face face) {
        float x0 = face.minX() / 16.0f;
        float y0 = face.minY() / 16.0f;
        float z0 = face.minZ() / 16.0f;
        float x1 = face.maxX() / 16.0f;
        float y1 = face.maxY() / 16.0f;
        float z1 = face.maxZ() / 16.0f;
        switch (face.direction()) {
            case NORTH -> positions(emitter, x1, y0, z0, x0, y0, z0,
                    x0, y1, z0, x1, y1, z0);
            case SOUTH -> positions(emitter, x0, y0, z1, x1, y0, z1,
                    x1, y1, z1, x0, y1, z1);
            case WEST -> positions(emitter, x0, y0, z0, x0, y0, z1,
                    x0, y1, z1, x0, y1, z0);
            case EAST -> positions(emitter, x1, y0, z1, x1, y0, z0,
                    x1, y1, z0, x1, y1, z1);
            case UP -> positions(emitter, x0, y1, z1, x1, y1, z1,
                    x1, y1, z0, x0, y1, z0);
            case DOWN -> positions(emitter, x0, y0, z0, x1, y0, z0,
                    x1, y0, z1, x0, y0, z1);
        }
    }

    private static void positions(QuadEmitter emitter,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3) {
        emitter.pos(0, x0, y0, z0);
        emitter.pos(1, x1, y1, z1);
        emitter.pos(2, x2, y2, z2);
        emitter.pos(3, x3, y3, z3);
    }

    /** Material quad lookup shared with the carver hologram renderer. */
    public static MaterialFaces materialFaces(String value) {
        MaterialCache cache = MATERIAL_CACHE.get();
        Object currentModelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (cache.modelSet != currentModelSet) {
            cache.modelSet = currentModelSet;
            cache.faces.clear();
        }
        return cache.faces.computeIfAbsent(value, ignored -> {
            BlockState state = parseBlockState(value);
            BlockStateModel model = Minecraft.getInstance().getModelManager()
                    .getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(0xEC11A5EL), parts);
            Map<Direction, List<BakedQuad>> faces = new java.util.EnumMap<>(Direction.class);
            for (Direction direction : Direction.values()) {
                List<BakedQuad> selected = new ArrayList<>();
                for (BlockStateModelPart part : parts) {
                    List<BakedQuad> directed = part.getQuads(direction);
                    if (!directed.isEmpty()) {
                        selected.addAll(directed);
                    } else {
                        for (BakedQuad quad : part.getQuads(null)) {
                            if (quad.direction() == direction) selected.add(quad);
                        }
                    }
                }
                faces.put(direction, List.copyOf(selected));
            }
            return new MaterialFaces(state, model, faces);
        });
    }

    /** Full state-string parser shared with hologram and effect code (properties included). */
    public static BlockState parseBlockState(String value) {
        try {
            int propertiesStart = value.indexOf('[');
            String id = propertiesStart < 0 ? value : value.substring(0, propertiesStart);
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            BlockState state = block.defaultBlockState();
            if (propertiesStart >= 0 && value.endsWith("]")) {
                String properties = value.substring(propertiesStart + 1, value.length() - 1);
                for (String assignment : properties.split(",")) {
                    int equals = assignment.indexOf('=');
                    if (equals < 1) continue;
                    String name = assignment.substring(0, equals);
                    String propertyValue = assignment.substring(equals + 1);
                    for (Property<?> property : state.getProperties()) {
                        if (property.getName().equals(name)) {
                            state = setProperty(state, property, propertyValue);
                        }
                    }
                }
            }
            return state;
        } catch (RuntimeException error) {
            return Blocks.STONE.defaultBlockState();
        }
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        return property.getValue(value)
                .map(parsed -> state.setValue(property, parsed))
                .orElse(state);
    }

    static float localU(MicrovoxelGreedyMesher.Direction direction,
                        float x, float y, float z) {
        return switch (direction) {
            case NORTH -> 1.0f - x;
            case SOUTH -> x;
            case WEST -> z;
            case EAST -> 1.0f - z;
            case UP, DOWN -> x;
        };
    }

    static float localV(MicrovoxelGreedyMesher.Direction direction,
                        float x, float y, float z) {
        return switch (direction) {
            case UP -> 1.0f - z;
            case DOWN -> z;
            default -> 1.0f - y;
        };
    }

    public record MaterialFaces(BlockState state, BlockStateModel model,
                                Map<Direction, List<BakedQuad>> faces) {
    }

    /**
     * Per-thread material face cache. Bounded LRU so terrain workers exploring many unique
     * block-state strings cannot grow memory without limit; entries are also dropped whenever
     * the vanilla model set changes (resource reload).
     */
    private static final class MaterialCache {
        private static final int MAX_MATERIALS = 256;
        private Object modelSet;
        private final java.util.LinkedHashMap<String, MaterialFaces> faces =
                new java.util.LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, MaterialFaces> eldest) {
                        return size() > MAX_MATERIALS;
                    }
                };
    }

    private record GeometryKey(long position, int revision, int paletteHash, int faceCount, int flags,
                               int fluidRevision) {
        private static final GeometryKey EMPTY = new GeometryKey(0L, -1, 0, 0, GENERAL_MATERIAL_FLAGS,
                Integer.MIN_VALUE);
    }

    public record UvPoint(float u, float v) {
    }

    public record UvPatch(float minU, float maxU, float minV, float maxV,
                          float[] localU, float[] localV, float[] atlasU, float[] atlasV) {
        public static UvPatch from(BakedQuad quad) {
            float[] localU = new float[4];
            float[] localV = new float[4];
            float[] atlasU = new float[4];
            float[] atlasV = new float[4];
            float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            MicrovoxelGreedyMesher.Direction direction =
                    MicrovoxelGreedyMesher.Direction.valueOf(quad.direction().name());
            for (int index = 0; index < 4; index++) {
                var position = quad.position(index);
                localU[index] = MicrovoxelSectionModel.localU(
                        direction, position.x(), position.y(), position.z());
                localV[index] = MicrovoxelSectionModel.localV(
                        direction, position.x(), position.y(), position.z());
                long packed = quad.packedUV(index);
                atlasU[index] = Float.intBitsToFloat((int) (packed >>> 32));
                atlasV[index] = Float.intBitsToFloat((int) packed);
                minU = Math.min(minU, localU[index]);
                maxU = Math.max(maxU, localU[index]);
                minV = Math.min(minV, localV[index]);
                maxV = Math.max(maxV, localV[index]);
            }
            if (maxU - minU <= 1.0E-5f || maxV - minV <= 1.0E-5f) {
                var sprite = quad.materialInfo().sprite();
                return new UvPatch(0, 1, 0, 1,
                        new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
                        new float[]{sprite.getU(0), sprite.getU(1), sprite.getU(1), sprite.getU(0)},
                        new float[]{sprite.getV(0), sprite.getV(0), sprite.getV(1), sprite.getV(1)});
            }
            return new UvPatch(minU, maxU, minV, maxV, localU, localV, atlasU, atlasV);
        }

        public UvPoint sample(MicrovoxelGreedyMesher.Direction direction,
                              float x, float y, float z) {
            float u = clamp((MicrovoxelSectionModel.localU(direction, x, y, z) - minU)
                    / (maxU - minU));
            float v = clamp((MicrovoxelSectionModel.localV(direction, x, y, z) - minV)
                    / (maxV - minV));
            float atlasUSample = 0;
            float atlasVSample = 0;
            for (int index = 0; index < 4; index++) {
                float vertexU = clamp((localU[index] - minU) / (maxU - minU));
                float vertexV = clamp((localV[index] - minV) / (maxV - minV));
                float weight = (vertexU < 0.5f ? 1 - u : u)
                        * (vertexV < 0.5f ? 1 - v : v);
                atlasUSample += atlasU[index] * weight;
                atlasVSample += atlasV[index] * weight;
            }
            return new UvPoint(atlasUSample, atlasVSample);
        }

        private static float clamp(float value) {
            return Math.max(0, Math.min(1, value));
        }
    }
}
