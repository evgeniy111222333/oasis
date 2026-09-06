package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelVisualShape;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Compiles portable volumes to ordinary item baked quads.
 *
 * <p>The resulting geometry goes through Minecraft's normal item submission path, so GUI, hand,
 * item-frame and dropped-item rendering all share one exact shape. Compiled meshes are immutable
 * and cached by volume content; no 4096-cell walk happens during draw submission.</p>
 */
public final class MicrovoxelItemModel {
    private static final int MAX_CACHE_ENTRIES = 192;
    private static final int MAX_CACHED_QUADS = 131_072;
    private static final int MAX_GENERATED_QUADS = 16_384;
    private static final Map<MicrovoxelVisualShape.Key, CompiledItem> CACHE =
            new LinkedHashMap<>(64, 0.75f, true);
    private static int cachedQuadCount;
    private static Object cachedModelSet;

    private MicrovoxelItemModel() {
    }

    public static CompiledItem compile(MicrovoxelItemData.Parsed parsed) {
        Object modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (modelSet != cachedModelSet) {
            synchronized (CACHE) {
                CACHE.clear();
                cachedQuadCount = 0;
                cachedModelSet = modelSet;
            }
        }

        MicrovoxelVolume volume = parsed.volume();
        MicrovoxelVisualShape.Snapshot visual = MicrovoxelVisualShape.snapshot(volume);
        MicrovoxelVisualShape.Key key = visual.key();
        synchronized (CACHE) {
            CompiledItem existing = CACHE.get(key);
            if (existing != null) return existing;
        }

        CompiledItem built = build(volume, visual);
        synchronized (CACHE) {
            CompiledItem raced = CACHE.get(key);
            if (raced != null) return raced;
            CACHE.put(key, built);
            cachedQuadCount += built.quads().size();
            trimCache();
        }
        return built;
    }

    public static CompiledItem apply(ItemStackRenderState.LayerRenderState layer,
                                     MicrovoxelItemData.Parsed parsed) {
        CompiledItem compiled = compile(parsed);
        layer.prepareQuadList().addAll(compiled.quads());
        layer.setUsesBlockLight(true);
        layer.setExtents(compiled.extents());
        if (compiled.particleMaterial() != null) {
            layer.setParticleMaterial(compiled.particleMaterial());
        }
        return compiled;
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
            cachedQuadCount = 0;
            cachedModelSet = null;
        }
    }

    private static CompiledItem build(MicrovoxelVolume volume,
                                      MicrovoxelVisualShape.Snapshot visual) {
        List<MicrovoxelGreedyMesher.Face> faces =
                MicrovoxelGreedyMesher.build(volume, volume::materialAt);
        List<BakedQuad> quads = new ArrayList<>(Math.min(faces.size() * 2, MAX_GENERATED_QUADS));
        Material.Baked particle = null;

        for (MicrovoxelGreedyMesher.Face face : faces) {
            if (face.material() <= 0 || face.material() >= volume.palette().size()) continue;
            String material = volume.palette().get(face.material());
            MicrovoxelSectionModel.MaterialFaces resolved =
                    MicrovoxelSectionModel.materialFaces(material);
            if (particle == null) particle = resolved.model().particleMaterial();

            Direction direction = Direction.valueOf(face.direction().name());
            List<BakedQuad> sources = resolved.faces().get(direction);
            if (sources == null || sources.isEmpty()) continue;
            for (BakedQuad source : sources) {
                if (quads.size() >= MAX_GENERATED_QUADS) {
                    // Additional source quads are normally material overlays, not missing
                    // geometry. Keeping the already emitted face preserves the exact silhouette
                    // while preventing a hostile item from allocating an unbounded mesh.
                    break;
                }
                quads.add(remap(source, face, direction));
            }
        }
        Vector3fc[] corners = corners(visual.bounds());
        Supplier<Vector3fc[]> extents = () -> corners;
        return new CompiledItem(
                List.copyOf(quads), particle, visual.key(), visual.bounds(), extents);
    }

    private static Vector3fc[] corners(MicrovoxelVisualShape.Bounds bounds) {
        return new Vector3fc[]{
                new Vector3f(bounds.minX(), bounds.minY(), bounds.minZ()),
                new Vector3f(bounds.maxX(), bounds.minY(), bounds.minZ()),
                new Vector3f(bounds.minX(), bounds.maxY(), bounds.minZ()),
                new Vector3f(bounds.maxX(), bounds.maxY(), bounds.minZ()),
                new Vector3f(bounds.minX(), bounds.minY(), bounds.maxZ()),
                new Vector3f(bounds.maxX(), bounds.minY(), bounds.maxZ()),
                new Vector3f(bounds.minX(), bounds.maxY(), bounds.maxZ()),
                new Vector3f(bounds.maxX(), bounds.maxY(), bounds.maxZ())
        };
    }

    private static BakedQuad remap(BakedQuad source, MicrovoxelGreedyMesher.Face face,
                                   Direction direction) {
        Vector3f[] positions = positions(face);
        MicrovoxelSectionModel.UvPatch patch = MicrovoxelSectionModel.UvPatch.from(source);
        long[] uv = new long[4];
        for (int index = 0; index < 4; index++) {
            Vector3f position = positions[index];
            MicrovoxelSectionModel.UvPoint sample =
                    patch.sample(face.direction(), position.x(), position.y(), position.z());
            uv[index] = packUv(sample.u(), sample.v());
        }

        BakedQuad.MaterialInfo sourceInfo = source.materialInfo();
        // Portable block pieces can contain several unrelated block materials. A vanilla item
        // tint table belongs only to the underlying BlockItem, so retaining its tint indices would
        // apply the wrong colour to other palette entries. Keep the exact atlas sprite and render
        // layer, but use the texture's authored colour.
        BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(
                sourceInfo.sprite(), sourceInfo.layer(), sourceInfo.itemRenderType(),
                -1, sourceInfo.shade(), sourceInfo.lightEmission());
        return new BakedQuad(
                positions[0], positions[1], positions[2], positions[3],
                uv[0], uv[1], uv[2], uv[3], direction, material);
    }

    private static Vector3f[] positions(MicrovoxelGreedyMesher.Face face) {
        float x0 = face.minX() / 16.0f;
        float y0 = face.minY() / 16.0f;
        float z0 = face.minZ() / 16.0f;
        float x1 = face.maxX() / 16.0f;
        float y1 = face.maxY() / 16.0f;
        float z1 = face.maxZ() / 16.0f;
        return switch (face.direction()) {
            case NORTH -> vertices(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            case SOUTH -> vertices(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            case WEST -> vertices(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            case EAST -> vertices(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
            case UP -> vertices(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            case DOWN -> vertices(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        };
    }

    private static Vector3f[] vertices(
            float x0, float y0, float z0, float x1, float y1, float z1,
            float x2, float y2, float z2, float x3, float y3, float z3) {
        return new Vector3f[]{
                new Vector3f(x0, y0, z0), new Vector3f(x1, y1, z1),
                new Vector3f(x2, y2, z2), new Vector3f(x3, y3, z3)
        };
    }

    private static long packUv(float u, float v) {
        return ((long) Float.floatToRawIntBits(u) << 32)
                | (Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    private static void trimCache() {
        var iterator = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_CACHE_ENTRIES || cachedQuadCount > MAX_CACHED_QUADS)
                && iterator.hasNext()) {
            Map.Entry<MicrovoxelVisualShape.Key, CompiledItem> eldest = iterator.next();
            cachedQuadCount -= eldest.getValue().quads().size();
            iterator.remove();
        }
    }

    public record CompiledItem(List<BakedQuad> quads, Material.Baked particleMaterial,
                               Object renderIdentity, MicrovoxelVisualShape.Bounds bounds,
                               Supplier<Vector3fc[]> extents) {
    }
}
