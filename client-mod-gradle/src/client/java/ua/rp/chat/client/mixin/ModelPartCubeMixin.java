package ua.rp.chat.client.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.Cube.class)
public class ModelPartCubeMixin {
    @Shadow
    public ModelPart.Polygon[] polygons;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void eclipse$onInit(int u, int v, float x, float y, float z, float sizeX, float sizeY, float sizeZ, float extraX, float extraY, float extraZ, boolean mirror, float textureWidth, float textureHeight, java.util.Set<?> directions, CallbackInfo ci) {
        if (polygons == null || polygons.length < 6) {
            return;
        }

        // Forearm and shin segments have v texture coordinate matching (v % 16 == 6)
        if (v % 16 != 6) {
            return;
        }

        // Find min and max Y across all vertices to calculate height
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (ModelPart.Polygon poly : polygons) {
            for (ModelPart.Vertex vertex : poly.vertices()) {
                float yVal = vertex.y();
                if (yVal < minY) minY = yVal;
                if (yVal > maxY) maxY = yVal;
            }
        }
        float height = maxY - minY;

        // Forearm/shin height is exactly 6.5f (or 6.5f + 2*extra for sleeves/pants)
        if (height > 6.0f && height < 7.2f) {
            System.out.println("[ECLIPSE-UV-CUBE] Found forearm/shin cube: v=" + v + ", height=" + height);
            for (int pIdx = 0; pIdx < polygons.length; pIdx++) {
                ModelPart.Polygon quad = polygons[pIdx];
                float sumY = 0.0f;
                for (ModelPart.Vertex vertex : quad.vertices()) {
                    sumY += vertex.y();
                }
                float avgY = sumY / quad.vertices().length;
                // Bottom cap face has avgY very close to maxY
                if (avgY > maxY - 0.1f) {
                    float shiftV = -6.0f;
                    float texHeight = 64.0f;
                    System.out.println("[ECLIPSE-UV-CUBE] Remapping bottom cap polygon " + pIdx + " for cube v=" + v);
                    for (int i = 0; i < quad.vertices().length; i++) {
                        ModelPart.Vertex vert = quad.vertices()[i];
                        polygons[pIdx].vertices()[i] = vert.remap(vert.u(), vert.v() + shiftV / texHeight);
                    }
                }
            }
        }
    }
}
