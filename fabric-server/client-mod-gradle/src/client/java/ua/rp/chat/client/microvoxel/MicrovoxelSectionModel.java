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
import ua.rp.chat.microvoxel.MicrovoxelGeometry;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelShape;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

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
    /** Shared mesher directions, cached so the per-face path never clones Direction.values(). */
    private static final MicrovoxelGreedyMesher.Direction[] SHARED_DIRECTIONS =
            MicrovoxelGreedyMesher.Direction.values();
    /**
     * Vanilla direction per shared mesher direction ordinal. Both enums declare
     * DOWN, UP, NORTH, SOUTH, WEST, EAST in that order, so a single array index replaces
     * {@code Direction.valueOf(name())} — no String allocation per emitted quad on the terrain
     * compilation workers. A mismatch is caught by {@link #verifyDirectionMapping()} at class
     * init.
     */
    private static final Direction[] MC_DIRECTIONS_BY_ORDINAL = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    static {
        verifyDirectionMapping();
    }

    private static void verifyDirectionMapping() {
        if (SHARED_DIRECTIONS.length != MC_DIRECTIONS_BY_ORDINAL.length) {
            throw new IllegalStateException("Microvoxel direction arity drifted from vanilla");
        }
        for (int index = 0; index < SHARED_DIRECTIONS.length; index++) {
            if (!SHARED_DIRECTIONS[index].name().equals(MC_DIRECTIONS_BY_ORDINAL[index].name())) {
                throw new IllegalStateException("Microvoxel direction order drifted from vanilla at "
                        + index + ": " + SHARED_DIRECTIONS[index] + " vs " + MC_DIRECTIONS_BY_ORDINAL[index]);
            }
        }
    }

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
        boolean hasGeometry = cached.volume.hasGeometry();
        // A fully shaped volume has no full-cube faces, but its shape channel still renders.
        if (mesh.isEmpty() && !hasGeometry) {
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
            Direction direction = MC_DIRECTIONS_BY_ORDINAL[face.direction().ordinal()];
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

        // Geometry channel: shaped cells emit their own sub-voxel mesh.
        MicrovoxelGeometry geometry = cached.volume.geometryOrNull();
        if (geometry != null) {
            for (int cell : geometry.shapedCellIndex()) {
                int shapeId = geometry.shapeAt(cell);
                if (shapeId == 0) continue;
                int material = cached.volume.materialIndex(cell);
                if (material <= 0 || material >= palette.size()) continue;
                emitShapeFaces(emitter, level, pos, cell,
                        MicrovoxelShape.byId(shapeId), palette.get(material));
            }
        }

        // Per-cell mining crack: the exact vanilla destroy_stage_N sprite on the mined cell.
        if (MicrovoxelClientState.crackOverlayEnabled()) {
            for (int[] crack : MicrovoxelClientState.cracksAt(pos)) {
                emitCrackCube(emitter, pos, crack[0], crack[1]);
            }
        }
    }

    /** Emits the sub-voxel greedy mesh of one shaped cell, culling flush boundary faces. */
    private static void emitShapeFaces(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
                                       int cell, MicrovoxelShape shape, String materialName) {
        int cellX = MicrovoxelVolume.x(cell);
        int cellY = MicrovoxelVolume.y(cell);
        int cellZ = MicrovoxelVolume.z(cell);
        MaterialFaces materialFaces = materialFaces(materialName);
        for (MicrovoxelGreedyMesher.Face shapeFace : shape.greedyFaces()) {
            if (shapeFaceCulled(pos, shape, shapeFace, cellX, cellY, cellZ)) continue;
            Direction direction = MC_DIRECTIONS_BY_ORDINAL[shapeFace.direction().ordinal()];
            List<BakedQuad> quads = materialFaces.faces.get(direction);
            if (quads == null || quads.isEmpty()) continue;
            for (BakedQuad quad : quads) {
                emitShapeMaterialQuad(emitter, level, pos, materialFaces.state, shapeFace,
                        direction, quad, cellX, cellY, cellZ);
            }
        }
    }

    /**
     * Culls a shape's boundary face only when the whole face is flush with the cell boundary, the
     * shape covers that cell face completely, and the neighbour sub-cell is solid. Interior and
     * sloped faces always render; hidden flush faces are skipped so they cannot z-fight the
     * neighbouring full cell or real block.
     */
    private static boolean shapeFaceCulled(BlockPos pos, MicrovoxelShape shape,
                                           MicrovoxelGreedyMesher.Face face,
                                           int cellX, int cellY, int cellZ) {
        int bx = cellX * 16;
        int by = cellY * 16;
        int bz = cellZ * 16;
        return switch (face.direction()) {
            case WEST -> face.minX() == 0 && shape.coversFaceFully(MicrovoxelShape.FACE_NEG_X)
                    && MicrovoxelClientState.shapeNeighborSolid(pos, bx - 1, by + face.minY(), bz + face.minZ());
            case EAST -> face.maxX() == 16 && shape.coversFaceFully(MicrovoxelShape.FACE_POS_X)
                    && MicrovoxelClientState.shapeNeighborSolid(pos, bx + 16, by + face.minY(), bz + face.minZ());
            case DOWN -> face.minY() == 0 && shape.coversFaceFully(MicrovoxelShape.FACE_NEG_Y)
                    && MicrovoxelClientState.shapeNeighborSolid(pos, bx + face.minX(), by - 1, bz + face.minZ());
            case UP -> face.maxY() == 16 && shape.coversFaceFully(MicrovoxelShape.FACE_POS_Y)
                    && MicrovoxelClientState.shapeNeighborSolid(pos, bx + face.minX(), by + 16, bz + face.minZ());
            case NORTH -> face.minZ() == 0 && shape.coversFaceFully(MicrovoxelShape.FACE_NEG_Z)
                    && MicrovoxelClientState.shapeNeighborSolid(pos, bx + face.minX(), by + face.minY(), bz - 1);
            case SOUTH -> face.maxZ() == 16 && shape.coversFaceFully(MicrovoxelShape.FACE_POS_Z)
                    && MicrovoxelClientState.shapeNeighborSolid(pos, bx + face.minX(), by + face.minY(), bz + 16);
        };
    }

    private static void emitShapeMaterialQuad(QuadEmitter emitter, BlockAndTintGetter level,
                                              BlockPos pos, BlockState materialState,
                                              MicrovoxelGreedyMesher.Face face, Direction direction,
                                              BakedQuad source, int cellX, int cellY, int cellZ) {
        emitter.fromBakedQuad(source);
        setShapeFacePositions(emitter, face, cellX, cellY, cellZ);
        setShapeFaceUv(emitter, face, source, cellX, cellY, cellZ);
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

    private static void setShapeFacePositions(QuadEmitter emitter, MicrovoxelGreedyMesher.Face face,
                                              int cellX, int cellY, int cellZ) {
        float ox = cellX / 16.0f;
        float oy = cellY / 16.0f;
        float oz = cellZ / 16.0f;
        float x0 = ox + face.minX() / 256.0f;
        float y0 = oy + face.minY() / 256.0f;
        float z0 = oz + face.minZ() / 256.0f;
        float x1 = ox + face.maxX() / 256.0f;
        float y1 = oy + face.maxY() / 256.0f;
        float z1 = oz + face.maxZ() / 256.0f;
        // Nudge every face slightly outward along its normal so a face flush with a neighbouring
        // full cell (or real block) wins the depth test instead of z-fighting its coplanar plane.
        switch (face.direction()) {
            case WEST -> {
                x0 -= SHAPE_EPS;
                x1 -= SHAPE_EPS;
            }
            case EAST -> {
                x0 += SHAPE_EPS;
                x1 += SHAPE_EPS;
            }
            case DOWN -> {
                y0 -= SHAPE_EPS;
                y1 -= SHAPE_EPS;
            }
            case UP -> {
                y0 += SHAPE_EPS;
                y1 += SHAPE_EPS;
            }
            case NORTH -> {
                z0 -= SHAPE_EPS;
                z1 -= SHAPE_EPS;
            }
            case SOUTH -> {
                z0 += SHAPE_EPS;
                z1 += SHAPE_EPS;
            }
        }
        switch (face.direction()) {
            case NORTH -> positions(emitter, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            case SOUTH -> positions(emitter, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            case WEST -> positions(emitter, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            case EAST -> positions(emitter, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
            case UP -> positions(emitter, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            case DOWN -> positions(emitter, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        }
    }

    /**
     * Maps the source quad's texture rectangle onto the shape face using per-cell local 0..1
     * coordinates, so every shaped cell shows the full material texture on its face instead of a
     * block-wide UV that only makes sense for a full cube.
     */
    private static void setShapeFaceUv(QuadEmitter emitter, MicrovoxelGreedyMesher.Face face,
                                       BakedQuad source, int cellX, int cellY, int cellZ) {
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < 4; vertex++) {
            long packed = source.packedUV(vertex);
            float u = Float.intBitsToFloat((int) (packed >>> 32));
            float v = Float.intBitsToFloat((int) packed);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        float ox = cellX / 16.0f;
        float oy = cellY / 16.0f;
        float oz = cellZ / 16.0f;
        for (int vertex = 0; vertex < 4; vertex++) {
            float localX = (emitter.x(vertex) - ox) * 16.0f;
            float localY = (emitter.y(vertex) - oy) * 16.0f;
            float localZ = (emitter.z(vertex) - oz) * 16.0f;
            float u = localU(face.direction(), localX, localY, localZ);
            float v = localV(face.direction(), localX, localY, localZ);
            emitter.uv(vertex, minU + u * (maxU - minU), minV + v * (maxV - minV));
        }
    }

    /** Outward inflation of the crack cube so it never z-fights the cell's material faces. */
    private static final float CRACK_INFLATE = 0.001f;
    /** Tiny outward nudge of shape faces to avoid z-fighting a flush full neighbour. */
    private static final float SHAPE_EPS = 0.0005f;

    private static volatile net.minecraft.client.renderer.texture.TextureAtlas destroyStagesAtlas;
    private static volatile net.minecraft.client.resources.model.sprite.Material.Baked[] destroyStages;

    /**
     * The vanilla {@code block/destroy_stage_N} sprite as a translucent material, cached per block
     * atlas (resource reloads swap the atlas instance). Returns null when the atlas is
     * unavailable, so a crack is skipped rather than drawn wrong.
     */
    @SuppressWarnings("deprecation")
    private static net.minecraft.client.resources.model.sprite.Material.Baked destroyStageMaterial(int stage) {
        if (stage < 0 || stage > ua.rp.chat.microvoxel.MicrovoxelCrack.MAX_STAGE) return null;
        try {
            net.minecraft.client.renderer.texture.TextureAtlas atlas = Minecraft.getInstance()
                    .getAtlasManager()
                    .getAtlasOrThrow(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (destroyStages == null || destroyStagesAtlas != atlas) {
                synchronized (MicrovoxelSectionModel.class) {
                    if (destroyStages == null || destroyStagesAtlas != atlas) {
                        net.minecraft.client.resources.model.sprite.Material.Baked[] built =
                                new net.minecraft.client.resources.model.sprite.Material.Baked[
                                        ua.rp.chat.microvoxel.MicrovoxelCrack.MAX_STAGE + 1];
                        for (int index = 0; index < built.length; index++) {
                            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = atlas.getSprite(
                                    net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                            "minecraft", "block/destroy_stage_" + index));
                            built[index] = new net.minecraft.client.resources.model.sprite.Material.Baked(
                                    sprite, true);
                        }
                        destroyStages = built;
                        destroyStagesAtlas = atlas;
                    }
                }
            }
            return destroyStages[stage];
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Draws the destroy-stage texture on exactly one 1/16 cell, all six faces (depth-culled). */
    private static void emitCrackCube(QuadEmitter emitter, BlockPos pos, int cell, int stage) {
        net.minecraft.client.resources.model.sprite.Material.Baked material = destroyStageMaterial(stage);
        if (material == null) return;
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = material.sprite();
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float x0 = pos.getX() + MicrovoxelVolume.x(cell) / 16.0f - CRACK_INFLATE;
        float y0 = pos.getY() + MicrovoxelVolume.y(cell) / 16.0f - CRACK_INFLATE;
        float z0 = pos.getZ() + MicrovoxelVolume.z(cell) / 16.0f - CRACK_INFLATE;
        float x1 = pos.getX() + (MicrovoxelVolume.x(cell) + 1) / 16.0f + CRACK_INFLATE;
        float y1 = pos.getY() + (MicrovoxelVolume.y(cell) + 1) / 16.0f + CRACK_INFLATE;
        float z1 = pos.getZ() + (MicrovoxelVolume.z(cell) + 1) / 16.0f + CRACK_INFLATE;
        crackFace(emitter, material, u0, u1, v0, v1, Direction.DOWN,
                x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        crackFace(emitter, material, u0, u1, v0, v1, Direction.UP,
                x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        crackFace(emitter, material, u0, u1, v0, v1, Direction.NORTH,
                x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
        crackFace(emitter, material, u0, u1, v0, v1, Direction.SOUTH,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        crackFace(emitter, material, u0, u1, v0, v1, Direction.WEST,
                x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        crackFace(emitter, material, u0, u1, v0, v1, Direction.EAST,
                x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
    }

    private static void crackFace(QuadEmitter emitter,
                                  net.minecraft.client.resources.model.sprite.Material.Baked material,
                                  float u0, float u1, float v0, float v1, Direction face,
                                  float x0, float y0, float z0, float x1, float y1, float z1,
                                  float x2, float y2, float z2, float x3, float y3, float z3) {
        emitter.materialBake(material, -1);
        positions(emitter, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3);
        emitter.uv(0, u0, v1);
        emitter.uv(1, u1, v1);
        emitter.uv(2, u1, v0);
        emitter.uv(3, u0, v0);
        emitter.nominalFace(face).cullFace(null).diffuseShade(true).emit();
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
                geometryProvider.fluidRevisionOf(pos), geometryProvider.crackRevisionOf(pos));
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
                               int fluidRevision, int crackRevision) {
        private static final GeometryKey EMPTY = new GeometryKey(0L, -1, 0, 0, GENERAL_MATERIAL_FLAGS,
                Integer.MIN_VALUE, 0);
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
            MicrovoxelGreedyMesher.Direction direction = SHARED_DIRECTIONS[quad.direction().ordinal()];
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
