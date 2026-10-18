package ua.rp.chat.client.carry;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import java.util.Set;

/** Native item layers expose the exact public mesh for a translucent placement preview. */
public interface CarryGhostState {
    boolean eclipse$hasCarryGhostQuads();
    void eclipse$emitCarryGhost(PoseStack pose, MultiBufferSource.BufferSource buffers,
                               Set<RenderType> used, int color, int light);
}
