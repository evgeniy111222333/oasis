package ua.rp.chat.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import ua.rp.chat.client.EclipseClientMod;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Exact authored cuboid faces, submitted through the existing vanilla item pipeline. */
public final class BlockbenchItemGeometry {
    private static final Set<String> ITEMS = Set.of("ancient_peasant_hammer", "carver_chisel_point",
            "carver_chisel_flat", "carver_bag", "carver_bag_no_point", "carver_bag_empty");
    private static final Map<Identifier, Compiled> CACHE = new HashMap<>();
    private static Object modelGeneration;

    private BlockbenchItemGeometry() { }

    /** Keeps the current transform, foil and tint state; replaces only its authored mesh. */
    public static Object apply(ItemStack stack, ItemStackRenderState.LayerRenderState layer) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("eclipseserver") || !ITEMS.contains(id.getPath())) return null;
        List<BakedQuad> inherited = layer.prepareQuadList();
        if (inherited.isEmpty()) return null;
        Minecraft client = Minecraft.getInstance();
        Object generation = client.getModelManager().getBlockStateModelSet();
        if (generation != modelGeneration) {
            CACHE.clear();
            modelGeneration = generation;
        }
        if (!CACHE.containsKey(id)) {
            Compiled compiled = null;
            Identifier resource = Identifier.fromNamespaceAndPath(id.getNamespace(),
                    "models/item/" + id.getPath() + ".json");
            try (var reader = new InputStreamReader(client.getResourceManager()
                    .getResource(resource).orElseThrow().open(), StandardCharsets.UTF_8)) {
                Decoded decoded = decode(JsonParser.parseReader(reader).getAsJsonObject());
                compiled = bake(decoded, inherited.getFirst().materialInfo());
            } catch (Exception failure) {
                EclipseClientMod.LOGGER.warn("Unable to load authored item mesh {}", resource, failure);
            }
            // Six fixed item ids bound this cache; a broken resource is reported once per reload.
            CACHE.put(id, compiled);
        }
        Compiled compiled = CACHE.get(id);
        if (compiled == null) return null;
        inherited.clear();
        inherited.addAll(compiled.quads());
        layer.setExtents(() -> compiled.extents());
        layer.setParticleMaterial(compiled.particle());
        layer.setUsesBlockLight(true);
        // The compiled object changes on resource reload, invalidating cached GUI item images.
        return compiled.renderIdentity();
    }

    public static Decoded decode(JsonObject root) {
        JsonObject geometry = root.getAsJsonObject("eclipse_geometry");
        if (geometry == null || geometry.get("version").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported authored geometry version");
        }
        JsonArray quads = geometry.getAsJsonArray("quads");
        if (quads == null || quads.isEmpty() || quads.size() > 8192) {
            throw new IllegalArgumentException("Invalid authored quad count");
        }
        List<Face> faces = new ArrayList<>(quads.size());
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (JsonElement entry : quads) {
            JsonObject quad = entry.getAsJsonObject();
            JsonArray positions = quad.getAsJsonArray("positions"), uvs = quad.getAsJsonArray("uv");
            if (positions.size() != 4 || uvs.size() != 4) {
                throw new IllegalArgumentException("A quad requires four positions and UV pairs");
            }
            Vector3f[] points = new Vector3f[4];
            float[][] uv = new float[4][2];
            for (int i = 0; i < 4; i++) {
                JsonArray point = positions.get(i).getAsJsonArray(), pair = uvs.get(i).getAsJsonArray();
                if (point.size() != 3 || pair.size() != 2) throw new IllegalArgumentException("Invalid vertex");
                points[i] = new Vector3f(number(point, 0), number(point, 1), number(point, 2));
                min.min(points[i]); max.max(points[i]);
                for (int j = 0; j < 2; j++) {
                    uv[i][j] = number(pair, j);
                    if (uv[i][j] < 0 || uv[i][j] > 16) throw new IllegalArgumentException("UV outside sprite");
                }
            }
            Direction direction = Direction.valueOf(quad.get("direction").getAsString().toUpperCase(Locale.ROOT));
            faces.add(new Face(points, uv, direction));
        }
        Vector3fc[] extents = new Vector3fc[8];
        for (int i = 0; i < 8; i++) {
            extents[i] = new Vector3f((i & 1) == 0 ? min.x : max.x,
                    (i & 2) == 0 ? min.y : max.y, (i & 4) == 0 ? min.z : max.z);
        }
        return new Decoded(List.copyOf(faces), extents);
    }

    private static float number(JsonArray values, int index) {
        float value = values.get(index).getAsFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite geometry");
        return value;
    }

    private static Compiled bake(Decoded decoded, BakedQuad.MaterialInfo source) {
        List<BakedQuad> quads = new ArrayList<>(decoded.faces().size());
        BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(source.sprite(), source.layer(),
                source.itemRenderType(), -1, true, source.lightEmission());
        for (Face face : decoded.faces()) {
            long[] uv = new long[4];
            for (int i = 0; i < 4; i++) {
                float u = source.sprite().getU(face.uv()[i][0] / 16f);
                float v = source.sprite().getV(face.uv()[i][1] / 16f);
                uv[i] = ((long) Float.floatToRawIntBits(u) << 32) | (Float.floatToRawIntBits(v) & 0xffffffffL);
            }
            Vector3f[] p = face.positions();
            quads.add(new BakedQuad(p[0], p[1], p[2], p[3], uv[0], uv[1], uv[2], uv[3], face.direction(), material));
        }
        return new Compiled(List.copyOf(quads), decoded.extents(), new Material.Baked(source.sprite(), false), new Object());
    }

    public record Face(Vector3f[] positions, float[][] uv, Direction direction) { }
    public record Decoded(List<Face> faces, Vector3fc[] extents) { }
    private record Compiled(List<BakedQuad> quads, Vector3fc[] extents, Material.Baked particle, Object renderIdentity) { }
}
