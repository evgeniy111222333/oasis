package ua.rp.chat.client.animation;

import ua.rp.chat.animation.LocomotionController;

/** An immutable frame belongs to the captured avatar, never to the shared player model. */
public interface PlayerAnimationRenderState {
    LocomotionController.Frame eclipse$animationFrame();
    void eclipse$setAnimationFrame(LocomotionController.Frame frame);
    PlayerTorsoSkeleton eclipse$animationSkeleton(boolean crouching);
}
