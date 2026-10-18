package ua.rp.chat.client.carry;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import java.util.Set;

/** Uses each quad's real atlas; 26.1 separates item, block and special texture sheets. */
public final class CarryGhostVertices {
    private CarryGhostVertices() {}
    public static void emit(PoseStack pose, BakedQuad quad, MultiBufferSource.BufferSource buffers,
                            Set<RenderType> used, int color, int light) {
        var type = RenderTypes.entityTranslucent(quad.materialInfo().sprite().atlasLocation());
        used.add(type);
        var vertices = buffers.getBuffer(type);
        for (int vertex = 0; vertex < 4; vertex++) {
            long uv = quad.packedUV(vertex);
            vertices.addVertex(pose.last(), quad.position(vertex)).setColor(color)
                    .setUv(Float.intBitsToFloat((int) (uv >>> 32)), Float.intBitsToFloat((int) uv))
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(pose.last(), quad.direction().getStepX(), quad.direction().getStepY(), quad.direction().getStepZ());
        }
    }
}
