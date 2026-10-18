package ua.rp.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class CarverChiselAssetsTest {
    private static final Set<String> DIRECTIONS =
            Set.of("north", "east", "south", "west", "up", "down");
    private static final Set<String> ITEMS =
            Set.of("carver_chisel_flat", "carver_chisel_point");

    public static void main(String[] args) throws Exception {
        Path assets = Path.of("src/main/resources/assets/eclipseserver");
        require(Files.isDirectory(assets), "Assets must resolve from the project dir: " + assets);
        for (String item : ITEMS) {
            verifyModel(assets, item);
            verifyTexture(assets, item);
        }
        System.out.println("CarverChiselAssetsTest passed");
    }

    private static void verifyModel(Path assets, String item) throws Exception {
        Path model = assets.resolve("models/item/" + item + ".json");
        JsonObject root = JsonParser.parseString(
                Files.readString(model, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray elements = root.getAsJsonArray("elements");
        require(elements != null && elements.size() > 0, item + " must declare elements");
        int faces = 0;
        for (JsonElement entry : elements) {
            JsonObject cube = entry.getAsJsonObject();
            double[] from = doubles(cube.getAsJsonArray("from"));
            double[] to = doubles(cube.getAsJsonArray("to"));
            require(from.length == 3 && to.length == 3
                            && from[0] < to[0] && from[1] < to[1] && from[2] < to[2],
                    item + " cube " + cube.get("name") + " must have valid bounds");
            JsonObject cubeFaces = cube.getAsJsonObject("faces");
            require(cubeFaces != null && !cubeFaces.entrySet().isEmpty(),
                    item + " cube " + cube.get("name") + " must declare faces");
            for (Map.Entry<String, JsonElement> face : cubeFaces.entrySet()) {
                require(DIRECTIONS.contains(face.getKey()),
                        item + " has unknown face " + face.getKey());
                double[] uv = doubles(face.getValue().getAsJsonObject().getAsJsonArray("uv"));
                require(uv.length == 4, item + " face uv must hold 4 coords");
                for (double component : uv) {
                    require(component >= 0.0 && component <= 16.0,
                            item + " face uv must stay inside 0..16, got " + component);
                }
                faces++;
            }
            if (cube.has("rotation")) {
                JsonObject rotation = cube.getAsJsonObject("rotation");
                double angle = rotation.get("angle").getAsDouble();
                require(Set.of("x", "y", "z").contains(rotation.get("axis").getAsString())
                                && angle >= -45.0 && angle <= 45.0
                                && Math.abs(angle * 2.0 - Math.round(angle * 2.0)) < 1.0e-9,
                        item + " cube " + cube.get("name") + " has illegal rotation");
            }
        }
        require(faces > 0, item + " must emit quads");
        JsonObject textures = root.getAsJsonObject("textures");
        require(textures != null && textures.has("particle"),
                item + " must declare textures with a particle");
    }

    private static void verifyTexture(Path assets, String item) throws Exception {
        Path texture = assets.resolve("textures/item/" + item + ".png");
        require(Files.isRegularFile(texture), item + " texture must exist");
        BufferedImage image = javax.imageio.ImageIO.read(texture.toFile());
        require(image != null && image.getWidth() == 128 && image.getHeight() == 128,
                item + " texture must decode as 128x128");
        require(image.getColorModel().hasAlpha(), item + " texture requires transparency");
    }

    private static double[] doubles(JsonArray array) {
        double[] result = new double[array.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = array.get(index).getAsDouble();
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
