package ua.rp.chat.client.animation;

public final class LegPose {
    public float bodyXRot;
    public float bodyY;
    public float bodyZRot;

    public float rightLegX;
    public float rightLegY;
    public float rightLegXRot;
    public float rightLegYRot;
    public float rightLegZRot;
    public float rightKneeXRot;

    public float leftLegX;
    public float leftLegY;
    public float leftLegXRot;
    public float leftLegYRot;
    public float leftLegZRot;
    public float leftKneeXRot;

    public float totalIntensity() {
        return Math.abs(bodyXRot) + Math.abs(bodyY) + Math.abs(bodyZRot)
                + Math.abs(rightLegX) + Math.abs(rightLegY) + Math.abs(rightLegXRot)
                + Math.abs(rightLegYRot) + Math.abs(rightLegZRot) + Math.abs(rightKneeXRot)
                + Math.abs(leftLegX) + Math.abs(leftLegY) + Math.abs(leftLegXRot)
                + Math.abs(leftLegYRot) + Math.abs(leftLegZRot) + Math.abs(leftKneeXRot);
    }
}
