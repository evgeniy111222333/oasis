package ua.rp.chat.client.animation;

import ua.rp.chat.animation.AnimationLibrary;
import ua.rp.chat.animation.LocomotionController;

/** Shares one immutable locomotion/contact solve between camera and avatar extraction. */
public final class PlayerAnimationSampler {
    private final LocomotionController controller;
    private final GroundedGait gait = new GroundedGait();
    private SampleKey previous;
    private LocomotionController.Frame frame;

    private record SampleKey(double seconds, LocomotionController.Motion motion,
                             double x, double y, double z, double scale, boolean preview) {}

    public PlayerAnimationSampler(AnimationLibrary library) {
        controller = new LocomotionController(library);
    }

    public LocomotionController.Frame sample(double seconds, LocomotionController.Motion motion,
            double x, double y, double z, double scale, boolean preview, GroundedGait.Ground ground) {
        SampleKey key = new SampleKey(seconds, motion, x, y, z, scale, preview);
        if (key.equals(previous)) return frame;
        frame = preview ? controller.sample(seconds, motion)
                : gait.sample(controller, seconds, motion, x, y, z, scale, ground);
        previous = key;
        return frame;
    }
}
