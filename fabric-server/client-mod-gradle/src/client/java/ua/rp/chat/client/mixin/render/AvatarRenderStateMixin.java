package ua.rp.chat.client.mixin.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements LocalPlayerRenderState {
    @Unique
    private boolean eclipse$localPlayer = false;

    @Override
    public boolean eclipse$isLocalPlayer() {
        return eclipse$localPlayer;
    }

    @Override
    public void eclipse$setLocalPlayer(boolean val) {
        this.eclipse$localPlayer = val;
    }
}
