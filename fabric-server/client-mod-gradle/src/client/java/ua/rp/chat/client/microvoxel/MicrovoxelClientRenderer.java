package ua.rp.chat.client.microvoxel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import ua.rp.chat.microvoxel.MicrovoxelAnchorRules;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * High-fidelity renderer for converted blocks.
 *
 * <p>The original prototype used the first quad's sprite and a synthetic 0..1 UV square. This
 * renderer keeps every baked quad for the requested face, reconstructs its exact atlas UVs,
 * honours its tint/shading/emission metadata and applies vertex-level ambient occlusion to the
 * generated micro geometry. The client state supplies immutable, dirty-chunk batches so normal
 * frames only consume already meshed faces.</p>
 */
public final class MicrovoxelClientRenderer {
    private static final double RENDER_DISTANCE = 144.0;
    private static final int MAX_DRAW_CALLS_PER_FRAME = 96_000;
    // One-cell cavities need depth, but must still read as stone/wood rather than a black hole.
    private static final float AO_STEP = 0.075f;
    private static final float MIN_AO = 0.76f;
    private static final Map<String, MaterialModel> MATERIALS = new HashMap<>();
    private static Object cachedModelSet;

    private MicrovoxelClientRenderer() {
    }

    public static void register() {
        MicrovoxelSectionModel.register();
        LevelRenderEvents.END_MAIN.register(MicrovoxelClientRenderer::render);
    }

    public static void clearMaterialCache() {
        MATERIALS.clear();
        cachedModelSet = null;
    }

    private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (!Boolean.getBoolean("rpchat.microvoxel.legacyRenderer")) {
            Set<RenderType> overlayTypes = new LinkedHashSet<>();
            renderSelection(context, minecraft, minecraft.gameRenderer.getMainCamera().position(), overlayTypes);
            for (RenderType type : overlayTypes) context.bufferSource().endBatch(type);
            return;
        }
        Object modelSet = minecraft.getModelManager().getBlockStateModelSet();
        if (cachedModelSet != modelSet) {
            MATERIALS.clear();
            cachedModelSet = modelSet;
        }

        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        double maxDistanceSquared = RENDER_DISTANCE * RENDER_DISTANCE;
        EnumMap<RenderPass, List<DrawCall>> drawCalls = new EnumMap<>(RenderPass.class);
        for (RenderPass pass : RenderPass.values()) drawCalls.put(pass, new ArrayList<>());

        int scheduled = 0;
        BlockPos lastPos = null;
        boolean lastPosValid = false;
        outer:
        for (MicrovoxelClientState.ChunkBatch batch
                : MicrovoxelClientState.batchesNear(camera.x, camera.z, RENDER_DISTANCE)) {
            for (MicrovoxelClientState.ChunkFace chunkFace : batch.faces()) {
                BlockPos position = chunkFace.position();
                if (lastPos == null || !lastPos.equals(position)) {
                    lastPos = position;
                    BlockState marker = minecraft.level.getBlockState(position);
                    lastPosValid = isRenderableAnchor(marker);
                }
                if (!lastPosValid) {
                    continue;
                }
                if (!context.levelRenderer().isSectionCompiledAndVisible(position)) continue;
                double dx = position.getX() + 0.5 - camera.x;
                double dy = position.getY() + 0.5 - camera.y;
                double dz = position.getZ() + 0.5 - camera.z;
                if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) continue;

                MicrovoxelGreedyMesher.Face face = chunkFace.face();
                int materialIndex = face.material();
                var palette = chunkFace.cached().volume.palette();
                String materialName = (materialIndex >= 0 && materialIndex < palette.size())
                        ? palette.get(materialIndex)
                        : null;
                if (materialName == null) continue;
                FaceMaterial material = faceMaterial(minecraft, materialName, face.direction(), position);
                if (material.layers.isEmpty()) continue;
                ResolvedLayer diagnosticLayer = material.layers.getFirst();
                MicrovoxelClientState.probeRender(position, face, materialName, material.layers.size(),
                        diagnosticLayer.renderType.toString(), diagnosticLayer.pass == RenderPass.TRANSLUCENT,
                        diagnosticLayer.uv.minU, diagnosticLayer.uv.maxU,
                        diagnosticLayer.uv.minV, diagnosticLayer.uv.maxV);

                for (ResolvedLayer layer : material.layers) {
                    // Gameplay geometry must stay watertight.  AO describes an edge; it must
                    // never be implemented by shrinking or recessing the actual voxel face.
                    drawCalls.get(layer.pass).add(new DrawCall(position, face, layer));
                    if (++scheduled >= MAX_DRAW_CALLS_PER_FRAME) break outer;
                }
            }
        }

        drawCalls.get(RenderPass.TRANSLUCENT).sort(java.util.Comparator.comparingDouble(
                (DrawCall call) -> {
                    double dx = call.position.getX() + 0.5 - camera.x;
                    double dy = call.position.getY() + 0.5 - camera.y;
                    double dz = call.position.getZ() + 0.5 - camera.z;
                    return dx * dx + dy * dy + dz * dz;
                }).reversed());

        Set<RenderType> usedTypes = new LinkedHashSet<>();
        for (RenderPass pass : RenderPass.values()) {
            for (DrawCall call : drawCalls.get(pass)) {
                VertexConsumer consumer = context.bufferSource().getBuffer(call.layer.renderType);
                usedTypes.add(call.layer.renderType);
                int packedLight = boostLight(LevelRenderer.getLightCoords(minecraft.level, call.position), call.layer.emission);
                Vertex[] vertices = faceVertices(call.face, 0.0f, 0.0f);
                int[] vertexColors = ambientColors(call.position, call.face, vertices, call.layer.color);
                emitFace(consumer, context.poseStack().last(), vertices, call.layer.uv,
                        call.face.direction(), call.position.getX() - camera.x,
                        call.position.getY() - camera.y, call.position.getZ() - camera.z,
                        packedLight, vertexColors);
            }
        }

        renderSelection(context, minecraft, camera, usedTypes);
        for (RenderType type : usedTypes) context.bufferSource().endBatch(type);
    }

    /**
     * Keeps the native marker and the two legacy migration anchors renderable. Air is accepted
     * only for the short client-prediction window before the authoritative REMOVE packet arrives.
     */
    public static boolean isRenderableAnchor(BlockState state) {
        return MicrovoxelAnchorRules.renderable(
                MicrovoxelBlocks.isMarker(state),
                state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID),
                state.is(net.minecraft.world.level.block.Blocks.LIGHT),
                state.isAir());
    }

    /** A thin, material-aware hover plate: no intrusive wire cube and no wall-visible outline. */
    private static void renderSelection(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
                                        Minecraft minecraft, Vec3 camera, Set<RenderType> usedTypes) {
        if (!MicrovoxelInteractionController.editing()) return;
        MicrovoxelRaycaster.Hit hit = MicrovoxelInteractionController.currentHit();
        MicrovoxelInteractionController.StandardTarget standard = MicrovoxelInteractionController.currentStandardTarget();
        if (hit == null && standard == null) return;

        BlockPos position;
        int cell;
        MicrovoxelGreedyMesher.Direction direction;
        UvPatch uv;
        if (hit != null) {
            position = new BlockPos(hit.entry().x(), hit.entry().y(), hit.entry().z());
            MicrovoxelClientState.CachedVolume cached = MicrovoxelClientState.get(position);
            if (cached == null) return;
            int materialIndex = cached.volume.materialAt(MicrovoxelVolume.x(hit.cell()),
                    MicrovoxelVolume.y(hit.cell()), MicrovoxelVolume.z(hit.cell()));
            if (materialIndex == 0) return;
            String materialName = cached.volume.palette().get(materialIndex);
            FaceMaterial material = faceMaterial(minecraft, materialName, hit.face(), position);
            if (material.layers.isEmpty()) return;
            cell = hit.cell();
            direction = hit.face();
            uv = material.layers.getFirst().uv;
        } else {
            position = standard.position();
            cell = standard.cell();
            direction = standard.face();
            List<FaceLayer> layers = resolveFaceLayers(minecraft, minecraft.level.getBlockState(position), direction);
            if (layers.isEmpty()) return;
            uv = layers.getFirst().uv;
        }

        MicrovoxelGreedyMesher.Face selected = cellFace(cell, direction, 1);
        float pulse = 0.60f + 0.18f * (float) Math.sin((minecraft.level.getGameTime() % 80L) / 80.0 * Math.PI * 2.0);
        int gold = 0xA0F3CF78;
        Vertex[] vertices = faceVertices(selected, 0.0045f, 0.0022f);
        int[] colors = new int[]{shade(gold, pulse), shade(gold, pulse), shade(gold, pulse), shade(gold, pulse)};
        RenderType overlay = RenderTypes.translucentMovingBlock();
        VertexConsumer consumer = context.bufferSource().getBuffer(overlay);
        usedTypes.add(overlay);
        emitFace(consumer, context.poseStack().last(), vertices, uv, selected.direction(),
                position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z,
                0x00F000F0, colors);

        List<MicrovoxelInteractionController.PreviewCell> preview =
                MicrovoxelInteractionController.brushPreview();
        if (preview.isEmpty()) return;
        Map<PreviewKey, MicrovoxelInteractionController.PreviewCell> lattice = new HashMap<>();
        for (MicrovoxelInteractionController.PreviewCell previewCell : preview) {
            int previewCellIndex = previewCell.cell();
            BlockPos previewPos = previewCell.position();
            lattice.put(new PreviewKey(
                            previewPos.getX() * 16 + MicrovoxelVolume.x(previewCellIndex),
                            previewPos.getY() * 16 + MicrovoxelVolume.y(previewCellIndex),
                            previewPos.getZ() * 16 + MicrovoxelVolume.z(previewCellIndex)),
                    previewCell);
        }
        int brushColor = shade(0x72FFD36E, pulse);
        int[] brushColors = {brushColor, brushColor, brushColor, brushColor};
        for (Map.Entry<PreviewKey, MicrovoxelInteractionController.PreviewCell> entry : lattice.entrySet()) {
            PreviewKey key = entry.getKey();
            MicrovoxelInteractionController.PreviewCell previewCell = entry.getValue();
            for (MicrovoxelGreedyMesher.Direction previewDirection : MicrovoxelGreedyMesher.Direction.values()) {
                if (lattice.containsKey(new PreviewKey(key.x + previewDirection.dx,
                        key.y + previewDirection.dy, key.z + previewDirection.dz))) continue;
                MicrovoxelGreedyMesher.Face previewFace =
                        cellFace(previewCell.cell(), previewDirection, 1);
                emitFace(consumer, context.poseStack().last(),
                        faceVertices(previewFace, 0.0015f, 0.001f), uv, previewDirection,
                        previewCell.position().getX() - camera.x,
                        previewCell.position().getY() - camera.y,
                        previewCell.position().getZ() - camera.z,
                        0x00F000F0, brushColors);
            }
        }
    }

    private static MicrovoxelGreedyMesher.Face cellFace(int cell, MicrovoxelGreedyMesher.Direction direction,
                                                         int material) {
        int x = MicrovoxelVolume.x(cell);
        int y = MicrovoxelVolume.y(cell);
        int z = MicrovoxelVolume.z(cell);
        return switch (direction) {
            case DOWN -> new MicrovoxelGreedyMesher.Face(direction, material, x, y, z, x + 1, y, z + 1);
            case UP -> new MicrovoxelGreedyMesher.Face(direction, material, x, y + 1, z, x + 1, y + 1, z + 1);
            case NORTH -> new MicrovoxelGreedyMesher.Face(direction, material, x, y, z, x + 1, y + 1, z);
            case SOUTH -> new MicrovoxelGreedyMesher.Face(direction, material, x, y, z + 1, x + 1, y + 1, z + 1);
            case WEST -> new MicrovoxelGreedyMesher.Face(direction, material, x, y, z, x, y + 1, z + 1);
            case EAST -> new MicrovoxelGreedyMesher.Face(direction, material, x + 1, y, z, x + 1, y + 1, z + 1);
        };
    }

    private record PreviewKey(int x, int y, int z) {
    }

    private static FaceMaterial faceMaterial(Minecraft minecraft, String materialName,
                                             MicrovoxelGreedyMesher.Direction face, BlockPos position) {
        MaterialModel material = MATERIALS.computeIfAbsent(materialName, name -> resolveMaterial(minecraft, name));
        FaceMaterial staticMaterial = material.staticFaceMaterials.get(face);
        if (staticMaterial != null) return staticMaterial;

        List<FaceLayer> templates = material.faces.computeIfAbsent(face,
                direction -> resolveFaceLayers(minecraft, material.state, direction));
        
        boolean hasTint = false;
        for (FaceLayer template : templates) {
            if (template.tintIndex >= 0) {
                hasTint = true;
                break;
            }
        }
        
        List<ResolvedLayer> layers = new ArrayList<>(templates.size());
        for (FaceLayer template : templates) {
            int color = 0xFFFFFFFF;
            if (template.tintIndex >= 0) {
                BlockTintSource tint = minecraft.getBlockColors().getTintSource(material.state, template.tintIndex);
                if (tint != null) color = 0xFF000000
                        | (tint.colorInWorld(material.state, minecraft.level, position) & 0xFFFFFF);
            }
            if (template.shade) color = shade(color, directionalShade(face));
            int emission = Math.max(material.state.getLightEmission(), template.emission);
            layers.add(new ResolvedLayer(template.uv, template.renderType, color, emission,
                    template.pass(emission)));
        }
        FaceMaterial faceMat = new FaceMaterial(List.copyOf(layers));
        if (!hasTint) {
            material.staticFaceMaterials.put(face, faceMat);
        }
        return faceMat;
    }

    private static MaterialModel resolveMaterial(Minecraft minecraft, String value) {
        BlockState state = parseBlockState(value);
        return new MaterialModel(state,
                new EnumMap<>(MicrovoxelGreedyMesher.Direction.class),
                new EnumMap<>(MicrovoxelGreedyMesher.Direction.class));
    }

    /**
     * Retains all baked layers (base, overlays and tint layers), not just quad zero. UVs are read
     * from the baked model in atlas space, so rotations, cropped textures and animation frames
     * are rendered exactly as the owning block model defines them.
     */
    private static List<FaceLayer> resolveFaceLayers(Minecraft minecraft, BlockState state,
                                                     MicrovoxelGreedyMesher.Direction face) {
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0xEC11A5EL), parts);
        Direction vanillaDirection = Direction.valueOf(face.name());
        List<FaceLayer> layers = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(vanillaDirection);
            if (quads.isEmpty()) {
                for (BakedQuad quad : part.getQuads(null)) {
                    if (quad.direction() == vanillaDirection) layers.add(FaceLayer.from(quad));
                }
            } else {
                for (BakedQuad quad : quads) layers.add(FaceLayer.from(quad));
            }
        }
        if (!layers.isEmpty()) return List.copyOf(layers);
        TextureAtlasSprite sprite = model.particleMaterial().sprite();
        return List.of(new FaceLayer(UvPatch.fromSprite(sprite),
                Sheets.cutoutBlockSheet(), -1, true, 0,
                false, false));
    }

    /**
     * Single blockstate-string parser for the legacy renderer. Delegates to the section model
     * instead of carrying a third copy of the same codec (the per-call warn log was dropped
     * deliberately: corrupt palette entries already surface through resync metrics, and this
     * path runs per material per frame).
     */
    private static BlockState parseBlockState(String value) {
        return MicrovoxelSectionModel.parseBlockState(value);
    }

    private static Vertex[] faceVertices(MicrovoxelGreedyMesher.Face face, float inset, float push) {
        float x0 = face.minX() / 16.0f;
        float y0 = face.minY() / 16.0f;
        float z0 = face.minZ() / 16.0f;
        float x1 = face.maxX() / 16.0f;
        float y1 = face.maxY() / 16.0f;
        float z1 = face.maxZ() / 16.0f;
        if (inset > 0.0f) {
            switch (face.direction()) {
                case UP, DOWN -> { x0 += inset; x1 -= inset; z0 += inset; z1 -= inset; }
                case NORTH, SOUTH -> { x0 += inset; x1 -= inset; y0 += inset; y1 -= inset; }
                case WEST, EAST -> { z0 += inset; z1 -= inset; y0 += inset; y1 -= inset; }
            }
        }
        float dx = face.direction().dx * push;
        float dy = face.direction().dy * push;
        float dz = face.direction().dz * push;
        return switch (face.direction()) {
            case NORTH -> new Vertex[]{v(x1, y0, z0, dx, dy, dz), v(x0, y0, z0, dx, dy, dz),
                    v(x0, y1, z0, dx, dy, dz), v(x1, y1, z0, dx, dy, dz)};
            case SOUTH -> new Vertex[]{v(x0, y0, z1, dx, dy, dz), v(x1, y0, z1, dx, dy, dz),
                    v(x1, y1, z1, dx, dy, dz), v(x0, y1, z1, dx, dy, dz)};
            case WEST -> new Vertex[]{v(x0, y0, z0, dx, dy, dz), v(x0, y0, z1, dx, dy, dz),
                    v(x0, y1, z1, dx, dy, dz), v(x0, y1, z0, dx, dy, dz)};
            case EAST -> new Vertex[]{v(x1, y0, z1, dx, dy, dz), v(x1, y0, z0, dx, dy, dz),
                    v(x1, y1, z0, dx, dy, dz), v(x1, y1, z1, dx, dy, dz)};
            case UP -> new Vertex[]{v(x0, y1, z1, dx, dy, dz), v(x1, y1, z1, dx, dy, dz),
                    v(x1, y1, z0, dx, dy, dz), v(x0, y1, z0, dx, dy, dz)};
            case DOWN -> new Vertex[]{v(x0, y0, z0, dx, dy, dz), v(x1, y0, z0, dx, dy, dz),
                    v(x1, y0, z1, dx, dy, dz), v(x0, y0, z1, dx, dy, dz)};
        };
    }

    private static Vertex v(float x, float y, float z, float dx, float dy, float dz) {
        return new Vertex(x + dx, y + dy, z + dz);
    }

    private static void emitFace(VertexConsumer consumer, PoseStack.Pose pose, Vertex[] vertices, UvPatch uv,
                                 MicrovoxelGreedyMesher.Direction direction,
                                 double baseX, double baseY, double baseZ, int light, int[] colors) {
        for (int index = 0; index < 4; index++) {
            Vertex vertex = vertices[index];
            UvPoint point = uv.sample(direction, vertex.x, vertex.y, vertex.z);
            consumer.addVertex(pose, (float) (baseX + vertex.x), (float) (baseY + vertex.y),
                            (float) (baseZ + vertex.z))
                    .setColor(colors[index]).setUv(point.u, point.v)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(pose, direction.dx, direction.dy, direction.dz);
        }
    }


    private static final ThreadLocal<int[]> COLOR_BUFFER = ThreadLocal.withInitial(() -> new int[4]);

    /** Per-vertex ambient occlusion sampled from the actual neighbouring microvolume topology. */
    private static int[] ambientColors(BlockPos base, MicrovoxelGreedyMesher.Face face,
                                       Vertex[] vertices, int color) {
        int[] result = COLOR_BUFFER.get();
        for (int index = 0; index < vertices.length; index++) {
            Vertex vertex = vertices[index];
            int occlusion = cornerOcclusion(base, face.direction(), vertex);
            result[index] = shade(color, Math.max(MIN_AO, 1.0f - AO_STEP * occlusion));
        }
        return result;
    }

    private static int cornerOcclusion(BlockPos base, MicrovoxelGreedyMesher.Direction direction, Vertex vertex) {
        int x = tangentCell(vertex.x);
        int y = tangentCell(vertex.y);
        int z = tangentCell(vertex.z);
        int ux = 0, uy = 0, uz = 0;
        int vx = 0, vy = 0, vz = 0;
        float u = localU(direction, vertex.x, vertex.y, vertex.z);
        float v = localV(direction, vertex.x, vertex.y, vertex.z);
        int uSign = u < 0.5f ? -1 : 1;
        int vSign = v < 0.5f ? -1 : 1;

        switch (direction) {
            case UP, DOWN -> { ux = uSign; vz = vSign; y = exteriorCell(vertex.y, direction.dy); }
            case NORTH, SOUTH -> { ux = uSign; vy = vSign; z = exteriorCell(vertex.z, direction.dz); }
            case WEST, EAST -> { uz = uSign; vy = vSign; x = exteriorCell(vertex.x, direction.dx); }
        }
        boolean sideU = MicrovoxelClientState.solidAt(base, x + ux, y + uy, z + uz);
        boolean sideV = MicrovoxelClientState.solidAt(base, x + vx, y + vy, z + vz);
        if (sideU && sideV) return 3;
        boolean corner = MicrovoxelClientState.solidAt(base, x + ux + vx, y + uy + vy, z + uz + vz);
        return (sideU ? 1 : 0) + (sideV ? 1 : 0) + (corner ? 1 : 0);
    }

    private static int tangentCell(float coordinate) {
        int result = (int) Math.floor(coordinate * 16.0f);
        return Math.max(0, Math.min(15, result));
    }

    private static int exteriorCell(float coordinate, int normal) {
        int plane = Math.round(coordinate * 16.0f);
        return normal > 0 ? plane : plane - 1;
    }

    private static float localU(MicrovoxelGreedyMesher.Direction direction, float x, float y, float z) {
        return switch (direction) {
            case NORTH -> 1.0f - x;
            case SOUTH -> x;
            case WEST -> z;
            case EAST -> 1.0f - z;
            case UP, DOWN -> x;
        };
    }

    private static float localV(MicrovoxelGreedyMesher.Direction direction, float x, float y, float z) {
        return switch (direction) {
            case UP -> 1.0f - z;
            case DOWN -> z;
            default -> 1.0f - y;
        };
    }

    private static int boostLight(int packedLight, int emission) {
        int block = Math.max(packedLight & 0xFFFF, Math.min(15, Math.max(0, emission)) << 4);
        return (packedLight & 0xFFFF0000) | block;
    }

    private static int shade(int color, float factor) {
        int alpha = (color >>> 24) & 0xFF;
        int red = Math.min(255, Math.round(((color >>> 16) & 0xFF) * factor));
        int green = Math.min(255, Math.round(((color >>> 8) & 0xFF) * factor));
        int blue = Math.min(255, Math.round((color & 0xFF) * factor));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static float directionalShade(MicrovoxelGreedyMesher.Direction face) {
        return switch (face) {
            case UP -> 1.0f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private enum RenderPass { OPAQUE, CUTOUT, EMISSIVE, TRANSLUCENT }

    private record MaterialModel(BlockState state,
                                 EnumMap<MicrovoxelGreedyMesher.Direction, List<FaceLayer>> faces,
                                 EnumMap<MicrovoxelGreedyMesher.Direction, FaceMaterial> staticFaceMaterials) {
    }

    private record FaceMaterial(List<ResolvedLayer> layers) {
    }

    private record ResolvedLayer(UvPatch uv, RenderType renderType, int color, int emission, RenderPass pass) {
    }

    private record DrawCall(BlockPos position, MicrovoxelGreedyMesher.Face face, ResolvedLayer layer) {
    }

    private record Vertex(float x, float y, float z) {
    }

    private record UvPoint(float u, float v) {
    }

    private record FaceLayer(UvPatch uv, RenderType renderType, int tintIndex, boolean shade, int emission,
                             boolean translucent, boolean animated) {
        private static FaceLayer from(BakedQuad quad) {
            BakedQuad.MaterialInfo info = quad.materialInfo();
            boolean translucent = (info.flags() & BakedQuad.FLAG_TRANSLUCENT) != 0;
            boolean animated = (info.flags() & BakedQuad.FLAG_ANIMATED) != 0;
            RenderType renderType = translucent ? RenderTypes.translucentMovingBlock() : info.itemRenderType();
            return new FaceLayer(UvPatch.from(quad), renderType, info.isTinted() ? info.tintIndex() : -1,
                    info.shade(), info.lightEmission(), translucent, animated);
        }

        private RenderPass pass(int resolvedEmission) {
            if (resolvedEmission > 0) return RenderPass.EMISSIVE;
            if (translucent) return RenderPass.TRANSLUCENT;
            // Animated sprites retain their own atlas animation; cutout uses its model-provided type.
            return renderType == Sheets.cutoutBlockSheet()
                    ? RenderPass.CUTOUT : RenderPass.OPAQUE;
        }
    }

    /** Atlas-space UV patch reconstructed from the four original baked vertices. */
    private record UvPatch(float minU, float maxU, float minV, float maxV,
                           float[] localU, float[] localV, float[] atlasU, float[] atlasV) {
        private static UvPatch from(BakedQuad quad) {
            float[] localU = new float[4];
            float[] localV = new float[4];
            float[] atlasU = new float[4];
            float[] atlasV = new float[4];
            float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            MicrovoxelGreedyMesher.Direction direction = MicrovoxelGreedyMesher.Direction.valueOf(quad.direction().name());
            for (int index = 0; index < 4; index++) {
                Vector3fc position = quad.position(index);
                localU[index] = MicrovoxelClientRenderer.localU(direction, position.x(), position.y(), position.z());
                localV[index] = MicrovoxelClientRenderer.localV(direction, position.x(), position.y(), position.z());
                long packed = quad.packedUV(index);
                atlasU[index] = Float.intBitsToFloat((int) (packed >>> 32));
                atlasV[index] = Float.intBitsToFloat((int) packed);
                minU = Math.min(minU, localU[index]);
                maxU = Math.max(maxU, localU[index]);
                minV = Math.min(minV, localV[index]);
                maxV = Math.max(maxV, localV[index]);
            }
            if (!(maxU - minU > 1.0E-5f) || !(maxV - minV > 1.0E-5f)) {
                return fromSprite(quad.materialInfo().sprite());
            }
            return new UvPatch(minU, maxU, minV, maxV, localU, localV, atlasU, atlasV);
        }

        private static UvPatch fromSprite(TextureAtlasSprite sprite) {
            return new UvPatch(0.0f, 1.0f, 0.0f, 1.0f,
                    new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
                    new float[]{sprite.getU(0), sprite.getU(1), sprite.getU(1), sprite.getU(0)},
                    new float[]{sprite.getV(0), sprite.getV(0), sprite.getV(1), sprite.getV(1)});
        }

        private UvPoint sample(MicrovoxelGreedyMesher.Direction direction, float x, float y, float z) {
            float u = clamp((MicrovoxelClientRenderer.localU(direction, x, y, z) - minU) / (maxU - minU));
            float v = clamp((MicrovoxelClientRenderer.localV(direction, x, y, z) - minV) / (maxV - minV));
            float atlasUSample = 0.0f;
            float atlasVSample = 0.0f;
            for (int index = 0; index < 4; index++) {
                float vertexU = clamp((localU[index] - minU) / (maxU - minU));
                float vertexV = clamp((localV[index] - minV) / (maxV - minV));
                float weightU = vertexU < 0.5f ? 1.0f - u : u;
                float weightV = vertexV < 0.5f ? 1.0f - v : v;
                float weight = weightU * weightV;
                atlasUSample += atlasU[index] * weight;
                atlasVSample += atlasV[index] * weight;
            }
            return new UvPoint(atlasUSample, atlasVSample);
        }

        private static float clamp(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }
    }
}
