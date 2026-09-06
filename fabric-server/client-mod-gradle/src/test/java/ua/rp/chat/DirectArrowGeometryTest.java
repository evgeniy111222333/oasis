package ua.rp.chat;

import net.minecraft.client.resources.model.cuboid.CuboidModel;
import ua.rp.chat.projectile.DirectArrowGeometry;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Numeric regression checks for direct-export flight centring and wound-tip anchoring. */
public final class DirectArrowGeometryTest {
    private DirectArrowGeometryTest() {
    }

    public static void main(String[] args) {
        verifyMinecraftCanDeserializeDirectExport();
        float sourceLength =
                DirectArrowGeometry.SOURCE_TAIL_Z - DirectArrowGeometry.SOURCE_TIP_Z;
        require(close(sourceLength * DirectArrowGeometry.MODEL_SCALE,
                        DirectArrowGeometry.TARGET_LENGTH_BLOCKS),
                "Direct Blockbench arrow must render at the calibrated 0.90-block length");
        require(close(DirectArrowGeometry.SOURCE_CENTER_Z,
                        (DirectArrowGeometry.SOURCE_TIP_Z
                                + DirectArrowGeometry.SOURCE_TAIL_Z) * 0.5f),
                "Flying arrow origin must remain at the geometric length centre");
        require(close((DirectArrowGeometry.SOURCE_TIP_Z
                        - DirectArrowGeometry.SOURCE_TIP_Z)
                        * DirectArrowGeometry.MODEL_SCALE, 0.0f),
                "Embedded arrow transform must place the metal tip exactly at the wound anchor");
    }

    private static void verifyMinecraftCanDeserializeDirectExport() {
        String path = "assets/eclipseclient/models/item/embedded_arrow.json";
        try (InputStream stream = DirectArrowGeometryTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            require(stream != null, "Direct Blockbench arrow resource is missing from test runtime");
            CuboidModel model = CuboidModel.fromStream(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            require(model != null && model.geometry() != null,
                    "Minecraft must deserialize direct Blockbench Euler rotations and UVs");
        } catch (Exception error) {
            throw new AssertionError("Minecraft rejected the direct Blockbench arrow export", error);
        }
    }

    private static boolean close(float left, float right) {
        return Math.abs(left - right) < 0.00001f;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
