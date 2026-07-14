package ua.rp.chat.client.microvoxel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShapeRenderer;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MicrovoxelClientRenderer {
    private static final double RENDER_DISTANCE = 144.0;
    private static final int MAX_FACES_PER_FRAME = 65_536;
    private static final Map<String, MaterialModel> MATERIALS = new HashMap<>();
    private static Object cachedModelSet;

    private MicrovoxelClientRenderer() {
    }

    public static void register() {
        LevelRenderEvents.END_MAIN.register(MicrovoxelClientRenderer::render);
    }

    public static void clearMaterialCache() {
        MATERIALS.clear();
        cachedModelSet = null;
    }

    private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Object modelSet = minecraft.getModelManager().getBlockStateModelSet();
        if (cachedModelSet != modelSet) {
            MATERIALS.clear();
            cachedModelSet = modelSet;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        double maxDistanceSquared = RENDER_DISTANCE * RENDER_DISTANCE;
        Set<RenderType> usedTypes = new HashSet<>();
        int renderedFaces = 0;

        for (Map.Entry<BlockPos, MicrovoxelClientState.CachedVolume> entry
                : MicrovoxelClientState.volumesNear(camera.x, camera.z, RENDER_DISTANCE)) {
            BlockPos position = entry.getKey();
            BlockState marker = minecraft.level.getBlockState(position);
            if (!marker.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)
                    && !marker.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                continue;
            }
            double dx = position.getX() + 0.5 - camera.x;
            double dy = position.getY() + 0.5 - camera.y;
            double dz = position.getZ() + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) continue;
            MicrovoxelClientState.CachedVolume cached = entry.getValue();
            int light = LevelRenderer.getLightCoords(minecraft.level, position);
            for (MicrovoxelGreedyMesher.Face face : cached.mesh) {
                if (++renderedFaces > MAX_FACES_PER_FRAME) break;
                String materialName = cached.volume.palette().get(face.material());
                FaceMaterial material = faceMaterial(minecraft, materialName, face.direction(), position);
                VertexConsumer consumer = context.bufferSource().getBuffer(material.renderType);
                usedTypes.add(material.renderType);
                emitFace(consumer, context.poseStack().last(), face, material.sprite,
                        position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z,
                        boostLight(light, material.emission), material.color);
            }
            if (renderedFaces > MAX_FACES_PER_FRAME) break;
        }

        renderSelection(context, camera, usedTypes);
        for (RenderType type : usedTypes) context.bufferSource().endBatch(type);
    }

    private static void renderSelection(
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
            Vec3 camera, Set<RenderType> usedTypes) {
        if (!MicrovoxelInteractionController.editing()) return;
        MicrovoxelRaycaster.Hit hit = MicrovoxelInteractionController.currentHit();
        if (hit == null) return;
        int cell = hit.cell();
        double minX = MicrovoxelVolumeAccess.x(cell) / 16.0 - 0.001;
        double minY = MicrovoxelVolumeAccess.y(cell) / 16.0 - 0.001;
        double minZ = MicrovoxelVolumeAccess.z(cell) / 16.0 - 0.001;
        var shape = Shapes.box(minX, minY, minZ, minX + 1.0 / 16.0 + 0.002,
                minY + 1.0 / 16.0 + 0.002, minZ + 1.0 / 16.0 + 0.002);
        RenderType lines = RenderTypes.lines();
        usedTypes.add(lines);
        ShapeRenderer.renderShape(context.poseStack(), context.bufferSource().getBuffer(lines), shape,
                hit.entry().x() - camera.x, hit.entry().y() - camera.y, hit.entry().z() - camera.z,
                0xFF42E8F5, 1.0f);
    }

    private static FaceMaterial faceMaterial(Minecraft minecraft, String materialName,
                                             MicrovoxelGreedyMesher.Direction face, BlockPos position) {
        MaterialModel material = MATERIALS.computeIfAbsent(materialName, name -> resolveMaterial(minecraft, name));
        FaceTemplate template = material.faces.computeIfAbsent(face, direction -> resolveFace(minecraft, material.state, direction));
        int color = 0xFFFFFFFF;
        if (template.tintIndex >= 0) {
            BlockTintSource tint = minecraft.getBlockColors().getTintSource(material.state, template.tintIndex);
            if (tint != null) color = 0xFF000000 | (tint.colorInWorld(material.state, minecraft.level, position) & 0xFFFFFF);
        }
        color = shade(color, switch (face) {
            case UP -> 1.0f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        });
        return new FaceMaterial(template.sprite, template.renderType, color, material.state.getLightEmission());
    }

    private static int boostLight(int packedLight, int emission) {
        int block = Math.max(packedLight & 0xFFFF, Math.min(15, Math.max(0, emission)) << 4);
        return (packedLight & 0xFFFF0000) | block;
    }

    private static int shade(int color, float factor) {
        int red = Math.min(255, Math.round(((color >>> 16) & 0xFF) * factor));
        int green = Math.min(255, Math.round(((color >>> 8) & 0xFF) * factor));
        int blue = Math.min(255, Math.round((color & 0xFF) * factor));
        return (color & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }

    private static MaterialModel resolveMaterial(Minecraft minecraft, String value) {
        BlockState state = parseBlockState(value);
        return new MaterialModel(state, new EnumMap<>(MicrovoxelGreedyMesher.Direction.class));
    }

    private static FaceTemplate resolveFace(Minecraft minecraft, BlockState state,
                                            MicrovoxelGreedyMesher.Direction face) {
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0xEC11A5EL), parts);
        Direction vanillaDirection = Direction.valueOf(face.name());
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(vanillaDirection);
            if (quads.isEmpty()) quads = part.getQuads(null);
            if (!quads.isEmpty()) {
                BakedQuad.MaterialInfo info = quads.get(0).materialInfo();
                return new FaceTemplate(info.sprite(), info.itemRenderType(), info.isTinted() ? info.tintIndex() : -1);
            }
        }
        return new FaceTemplate(model.particleMaterial().sprite(),
                RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS), -1);
    }

    private static BlockState parseBlockState(String value) {
        try {
            int propertiesStart = value.indexOf('[');
            String identifierText = propertiesStart < 0 ? value : value.substring(0, propertiesStart);
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(identifierText));
            BlockState state = block.defaultBlockState();
            if (propertiesStart >= 0 && value.endsWith("]")) {
                String properties = value.substring(propertiesStart + 1, value.length() - 1);
                for (String assignment : properties.split(",")) {
                    int equals = assignment.indexOf('=');
                    if (equals < 1) continue;
                    String name = assignment.substring(0, equals);
                    String propertyValue = assignment.substring(equals + 1);
                    for (Property<?> property : state.getProperties()) {
                        if (property.getName().equals(name)) state = setProperty(state, property, propertyValue);
                    }
                }
            }
            return state;
        } catch (RuntimeException error) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Invalid block state " + value + ": " + error.getMessage());
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    private static void emitFace(VertexConsumer consumer, PoseStack.Pose pose,
                                 MicrovoxelGreedyMesher.Face face, TextureAtlasSprite sprite,
                                 double baseX, double baseY, double baseZ, int light, int color) {
        float x0 = (float) (baseX + face.minX() / 16.0);
        float y0 = (float) (baseY + face.minY() / 16.0);
        float z0 = (float) (baseZ + face.minZ() / 16.0);
        float x1 = (float) (baseX + face.maxX() / 16.0);
        float y1 = (float) (baseY + face.maxY() / 16.0);
        float z1 = (float) (baseZ + face.maxZ() / 16.0);
        switch (face.direction()) {
            case NORTH -> quad(consumer, pose, sprite, face.direction(), light, color,
                    x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, baseX, baseY, baseZ);
            case SOUTH -> quad(consumer, pose, sprite, face.direction(), light, color,
                    x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, baseX, baseY, baseZ);
            case WEST -> quad(consumer, pose, sprite, face.direction(), light, color,
                    x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, baseX, baseY, baseZ);
            case EAST -> quad(consumer, pose, sprite, face.direction(), light, color,
                    x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, baseX, baseY, baseZ);
            case UP -> quad(consumer, pose, sprite, face.direction(), light, color,
                    x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, baseX, baseY, baseZ);
            case DOWN -> quad(consumer, pose, sprite, face.direction(), light, color,
                    x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, baseX, baseY, baseZ);
        }
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, TextureAtlasSprite sprite,
                             MicrovoxelGreedyMesher.Direction direction, int light, int color,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             double baseX, double baseY, double baseZ) {
        emit(consumer, pose, sprite, direction, x0, y0, z0, baseX, baseY, baseZ, light, color);
        emit(consumer, pose, sprite, direction, x1, y1, z1, baseX, baseY, baseZ, light, color);
        emit(consumer, pose, sprite, direction, x2, y2, z2, baseX, baseY, baseZ, light, color);
        emit(consumer, pose, sprite, direction, x3, y3, z3, baseX, baseY, baseZ, light, color);
    }

    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, TextureAtlasSprite sprite,
                             MicrovoxelGreedyMesher.Direction direction, float x, float y, float z,
                             double baseX, double baseY, double baseZ, int light, int color) {
        float localX = (float) (x - baseX);
        float localY = (float) (y - baseY);
        float localZ = (float) (z - baseZ);
        float u = switch (direction) {
            case NORTH -> 1.0f - localX;
            case SOUTH -> localX;
            case WEST -> localZ;
            case EAST -> 1.0f - localZ;
            case UP, DOWN -> localX;
        };
        float v = switch (direction) {
            case UP -> 1.0f - localZ;
            case DOWN -> localZ;
            default -> 1.0f - localY;
        };
        consumer.addVertex(pose, x, y, z).setColor(color).setUv(sprite.getU(u), sprite.getV(v))
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, direction.dx, direction.dy, direction.dz);
    }

    private record MaterialModel(BlockState state, EnumMap<MicrovoxelGreedyMesher.Direction, FaceTemplate> faces) {
    }

    private record FaceTemplate(TextureAtlasSprite sprite, RenderType renderType, int tintIndex) {
    }

    private record FaceMaterial(TextureAtlasSprite sprite, RenderType renderType, int color, int emission) {
    }

    private static final class MicrovoxelVolumeAccess {
        private static int x(int cell) { return cell & 15; }
        private static int z(int cell) { return (cell >>> 4) & 15; }
        private static int y(int cell) { return (cell >>> 8) & 15; }
    }
}
