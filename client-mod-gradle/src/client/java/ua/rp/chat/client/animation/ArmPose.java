package ua.rp.chat.client.animation;

public final class ArmPose {
    public float bodyXRot;
    public float bodyYRot;
    public float bodyZRot;
    public float bodyX;
    public float bodyY;
    public float bodyZ;

    public float rightArmXRot;
    public float rightArmYRot;
    public float rightArmZRot;
    public float rightArmX;
    public float rightArmY;
    public float rightArmZ;
    public float rightForearmXRot;

    public float leftArmXRot;
    public float leftArmYRot;
    public float leftArmZRot;
    public float leftArmX;
    public float leftArmY;
    public float leftArmZ;
    public float leftForearmXRot;

    public float totalIntensity() {
        return Math.abs(bodyXRot) + Math.abs(bodyYRot) + Math.abs(bodyZRot)
                + Math.abs(rightArmXRot) + Math.abs(rightArmYRot) + Math.abs(rightArmZRot)
                + Math.abs(leftArmXRot) + Math.abs(leftArmYRot) + Math.abs(leftArmZRot)
                + Math.abs(rightForearmXRot) + Math.abs(leftForearmXRot);
    }
}
