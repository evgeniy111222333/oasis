package ua.rp.chat.client.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import ua.rp.chat.BreathingTorsoLayout;

/** Keeps independent back attachments outside the breathing skin surface. */
public final class BreathingAttachmentOffset {
    private static final int UPPER_BACK_RING = 2;
    private static final float CLEARANCE_MULTIPLIER = 1.10f;

    private BreathingAttachmentOffset() {
    }

    public static float backOffsetPixels(AvatarRenderState state) {
        if (state == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return 0.0f;
        }

        BreathingPoseState.Sample sample = BreathingPoseState.sample(state);
        BreathingTorsoLayout.Bounds upperBack = BreathingTorsoLayout.bounds(
                UPPER_BACK_RING,
                sample.respiration().phase(), sample.respiration().intensity(),
                sample.calm(), sample.firstPerson(), false);
        return Math.max(0.0f, upperBack.maxZ() - BreathingTorsoLayout.BODY_HALF_DEPTH)
                * CLEARANCE_MULTIPLIER;
    }
}
