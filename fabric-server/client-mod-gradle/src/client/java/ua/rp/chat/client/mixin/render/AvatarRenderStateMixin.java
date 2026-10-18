package ua.rp.chat.client.mixin.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements LocalPlayerRenderState, ua.rp.chat.client.animation.PlayerAnimationRenderState {
    @Unique private ua.rp.chat.animation.LocomotionController.Frame eclipse$animation;
    @Unique private ua.rp.chat.client.animation.PlayerTorsoSkeleton eclipse$skeleton;
    @Unique private boolean eclipse$skeletonCrouching;

    @Override public ua.rp.chat.animation.LocomotionController.Frame eclipse$animationFrame() { return eclipse$animation; }
    @Override public void eclipse$setAnimationFrame(ua.rp.chat.animation.LocomotionController.Frame frame) {
        eclipse$animation = frame;
        eclipse$skeleton = null;
    }
    @Override public ua.rp.chat.client.animation.PlayerTorsoSkeleton eclipse$animationSkeleton(boolean crouching) {
        if (eclipse$animation == null || !eclipse$animation.active()) return null;
        if (eclipse$skeleton == null || eclipse$skeletonCrouching != crouching) {
            eclipse$skeleton = new ua.rp.chat.client.animation.PlayerTorsoSkeleton(eclipse$animation.pose());
            eclipse$skeleton.groundFootContacts(eclipse$animation.pose(), crouching);
            eclipse$skeletonCrouching = crouching;
        }
        return eclipse$skeleton;
    }
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
