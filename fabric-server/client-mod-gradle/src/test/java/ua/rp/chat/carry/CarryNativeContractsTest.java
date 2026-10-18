package ua.rp.chat.carry;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import com.google.gson.JsonParser;
import ua.rp.chat.client.carry.CarryFeedback;

/** Verifies actual 26.1.2 method targets and shipped artwork without a client process. */
public final class CarryNativeContractsTest {
    public static void main(String[] args) throws Exception {
        method("net.minecraft.client.Minecraft", "startUseItem");
        method("net.minecraft.client.Minecraft", "startAttack");
        method("net.minecraft.client.Minecraft", "continueAttack", "boolean");
        method("net.minecraft.client.Minecraft", "shouldEntityAppearGlowing", "net.minecraft.world.entity.Entity");
        method("net.minecraft.client.renderer.entity.EntityRenderer", "extractRenderState",
                "net.minecraft.world.entity.Entity", "net.minecraft.client.renderer.entity.state.EntityRenderState", "float");
        method("net.minecraft.client.renderer.entity.DisplayRenderer", "extractRenderState",
                "net.minecraft.world.entity.Display", "net.minecraft.client.renderer.entity.state.DisplayEntityRenderState", "float");
        method("net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState", "applyTransform",
                "com.mojang.blaze3d.vertex.PoseStack$Pose");
        method("net.minecraft.world.entity.Display$ItemDisplay", "getItemStack");
        try (var input = CarryNativeContractsTest.class.getResourceAsStream("/assets/eclipseclient/textures/gui/carry/concept_01.png")) {
            if (input == null) throw new AssertionError("Chosen concept artwork is not packaged");
            byte[] bytes = input.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!sha.equals("38b6681d69f0ab29a6b145910eadc3459362673467105f0c0d247b9e77d7dace"))
                throw new AssertionError("The shipped image is not the user's selected concept 01");
            var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image.getWidth() != CarryFeedback.TEXTURE_SIZE || image.getHeight() != CarryFeedback.TEXTURE_SIZE
                    || !image.getColorModel().hasAlpha()) throw new AssertionError("Wrong texture size/alpha");
            int transparent = 0, visible = 0;
            for (int y = CarryFeedback.V; y < CarryFeedback.V + CarryFeedback.SOURCE_HEIGHT; y++)
                for (int x = CarryFeedback.U; x < CarryFeedback.U + CarryFeedback.SOURCE_WIDTH; x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    if (alpha == 0) transparent++;
                    if (alpha > 200) visible++;
                }
            if (transparent < 1000 || visible < 1000) throw new AssertionError("Action UV region is blank or opaque");
        }
        try (var input = CarryNativeContractsTest.class.getResourceAsStream("/eclipseclient.mixins.json")) {
            var mixins = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client");
            if (!mixins.contains(new com.google.gson.JsonPrimitive("carry.CarryMinecraftMixin"))
                    || !mixins.contains(new com.google.gson.JsonPrimitive("carry.CarryEntityRendererMixin"))
                    || !mixins.contains(new com.google.gson.JsonPrimitive("carry.CarryDisplayRendererMixin")))
                throw new AssertionError("Carry input/outline mixins aren't registered");
        }
        System.out.println("CarryNativeContractsTest: real 26.1.2 mixin/invoker targets, registered hooks and original transparent concept-01 asset passed");
    }
    private static Method method(String type, String name, String... parameters) throws Exception {
        Class<?>[] args = new Class<?>[parameters.length];
        for (int i = 0; i < args.length; i++) args[i] = switch (parameters[i]) {
            case "float" -> float.class; case "boolean" -> boolean.class;
            default -> Class.forName(parameters[i], false, CarryNativeContractsTest.class.getClassLoader());
        };
        return Class.forName(type, false, CarryNativeContractsTest.class.getClassLoader()).getDeclaredMethod(name, args);
    }
}
