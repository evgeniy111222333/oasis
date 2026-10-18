package ua.rp.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ua.rp.chat.client.render.BlockbenchItemGeometry;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises the actual mesh decoder without a graphics context. */
public final class BlockbenchItemGeometryTest {
    public static void main(String[] args) throws Exception {
        String[] items = {"ancient_peasant_hammer", "carver_chisel_point", "carver_chisel_flat",
                "carver_bag", "carver_bag_no_point", "carver_bag_empty"};
        int[] expected = {978, 288, 264, 1650, 1362, 600};
        for (int i = 0; i < items.length; i++) {
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/eclipseserver/models/item/" + items[i] + ".json"))).getAsJsonObject();
            var decoded = BlockbenchItemGeometry.decode(root);
            require(decoded.faces().size() == expected[i], "Missing authored faces: " + items[i]);
            require(decoded.extents().length == 8, "Item bounds must include every corner");
            for (var face : decoded.faces()) {
                for (var point : face.positions()) require(point.isFinite(), "Non-finite decoded position");
            }
            JsonObject broken = root.deepCopy();
            broken.getAsJsonObject("eclipse_geometry").getAsJsonArray("quads").get(0).getAsJsonObject()
                    .getAsJsonArray("uv").get(0).getAsJsonArray().set(0, JsonParser.parseString("17"));
            boolean rejected = false;
            try { BlockbenchItemGeometry.decode(broken); }
            catch (IllegalArgumentException expectedFailure) { rejected = true; }
            require(rejected, "Bad UVs must fail before touching a render layer");
        }
        System.out.println("BlockbenchItemGeometryTest passed: all 5142 authored faces and invalid UV rejection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
