package ua.rp.chat.client.mixin;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements LocalPlayerRenderState {
    @Unique
    private boolean oasis$localPlayer = false;

    @Override
    public boolean oasis$isLocalPlayer() {
        return oasis$localPlayer;
    }

    @Override
    public void oasis$setLocalPlayer(boolean val) {
        this.oasis$localPlayer = val;
    }
}
