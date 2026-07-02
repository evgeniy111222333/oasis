package ua.rp.chat.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.client.vitals.VitalsClientState;

public final class ArmRuntimeState {
    private boolean hasPosition;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private boolean wasOnGround = true;
    private boolean wasUsingItem;
    private boolean wasSwinging;

    private double horizontalSpeed;
    private float speed01;
    private float smoothedSpeed01;
    private float stridePhase;
    private float breathPhase;
    private float idlePhase;
    private float yawDelta;
    private float pitchDelta;
    private float yawInertia;
    private float pitchInertia;
    private float landingImpulse;
    private float actionImpulse;
    private int airborneTicks;
    private String movementMode = "idle";
    private ItemMotionProfile itemProfile = new ItemMotionProfile("empty", "empty", 0.0f, 0.0f, 0.0f, 0.0f);

    public void update(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            reset();
            return;
        }

        LocalPlayer player = client.player;
        updateMotion(player);
        updateLook(player);
        updatePhases(player);
        updateImpulses(player);
        itemProfile = ItemMotionProfile.classify(player.getMainHandItem(), player.getOffhandItem(), player.isUsingItem());
    }

    private void updateMotion(LocalPlayer player) {
        double moved = 0.0;
        if (hasPosition) {
            double dx = player.getX() - lastX;
            double dz = player.getZ() - lastZ;
            if (Math.abs(player.getY() - lastY) < 4.0) {
                moved = Math.sqrt(dx * dx + dz * dz);
            }
        }
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        hasPosition = true;

        Vec3 velocity = player.getDeltaMovement();
        double velocitySpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        horizontalSpeed = Math.max(moved, velocitySpeed * 0.55);
        float targetSpeed = clamp((float) (horizontalSpeed / 0.145), 0.0f, player.isSprinting() ? 1.45f : 1.05f);
        speed01 = targetSpeed;
        smoothedSpeed01 += (targetSpeed - smoothedSpeed01) * 0.22f;

        if (!player.onGround()) {
            movementMode = "airborne";
            airborneTicks++;
        } else {
            airborneTicks = 0;
            if (player.isCrouching() && targetSpeed > 0.08f) {
                movementMode = "crouch_walk";
            } else if (player.isCrouching()) {
                movementMode = "crouch";
            } else if (player.isSprinting() && targetSpeed > 0.20f) {
                movementMode = "sprint";
            } else if (targetSpeed > 0.18f) {
                movementMode = "walk";
            } else {
                movementMode = "idle";
            }
        }
    }

    private void updateLook(LocalPlayer player) {
        if (Float.isNaN(lastYaw)) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            yawDelta = 0.0f;
            pitchDelta = 0.0f;
            return;
        }
        yawDelta = wrapDegrees(player.getYRot() - lastYaw);
        pitchDelta = player.getXRot() - lastPitch;
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();

        yawInertia += (clamp(yawDelta / 38.0f, -1.0f, 1.0f) - yawInertia) * 0.28f;
        yawInertia *= 0.86f;
        pitchInertia += (clamp(pitchDelta / 34.0f, -1.0f, 1.0f) - pitchInertia) * 0.24f;
        pitchInertia *= 0.88f;
    }

    private void updatePhases(LocalPlayer player) {
        float sprint = "sprint".equals(movementMode) ? 1.0f : 0.0f;
        float stepRate = 0.060f + smoothedSpeed01 * (0.120f + sprint * 0.060f);
        if ("airborne".equals(movementMode)) {
            stepRate *= 0.24f;
        }
        stridePhase = wrapUnit(stridePhase + stepRate);

        float fatigue = clamp(VitalsClientState.getFatigue() / 100.0f, 0.0f, 1.0f);
        float breathDebt = clamp(VitalsClientState.getBreathDebt() / 100.0f, 0.0f, 1.0f);
        float breathRate = 0.0105f + smoothedSpeed01 * 0.0045f + fatigue * 0.015f + breathDebt * 0.010f;
        breathPhase = wrapUnit(breathPhase + breathRate);
        idlePhase = wrapUnit(idlePhase + 0.0065f + smoothedSpeed01 * 0.002f);
    }

    private void updateImpulses(LocalPlayer player) {
        if (player.onGround() && !wasOnGround) {
            landingImpulse = Math.max(landingImpulse, clamp((float) (player.fallDistance / 5.2f), 0.0f, 1.0f));
        }
        wasOnGround = player.onGround();

        boolean using = player.isUsingItem();
        if (wasUsingItem && !using) {
            actionImpulse = Math.max(actionImpulse, 0.42f);
        }
        wasUsingItem = using;

        boolean swinging = player.swinging;
        if (swinging && !wasSwinging) {
            actionImpulse = Math.max(actionImpulse, 0.68f);
        }
        wasSwinging = swinging;

        landingImpulse *= 0.78f;
        actionImpulse *= 0.72f;
    }

    public void reset() {
        hasPosition = false;
        horizontalSpeed = 0.0;
        speed01 = 0.0f;
        smoothedSpeed01 = 0.0f;
        stridePhase = 0.0f;
        breathPhase = 0.0f;
        idlePhase = 0.0f;
        yawDelta = 0.0f;
        pitchDelta = 0.0f;
        yawInertia = 0.0f;
        pitchInertia = 0.0f;
        landingImpulse = 0.0f;
        actionImpulse = 0.0f;
        airborneTicks = 0;
        movementMode = "idle";
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        wasUsingItem = false;
        wasSwinging = false;
        itemProfile = new ItemMotionProfile("empty", "empty", 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public double horizontalSpeed() {
        return horizontalSpeed;
    }

    public float speed01() {
        return speed01;
    }

    public float smoothedSpeed01() {
        return smoothedSpeed01;
    }

    public float stridePhase() {
        return stridePhase;
    }

    public float breathPhase() {
        return breathPhase;
    }

    public float idlePhase() {
        return idlePhase;
    }

    public float yawDelta() {
        return yawDelta;
    }

    public float pitchDelta() {
        return pitchDelta;
    }

    public float yawInertia() {
        return yawInertia;
    }

    public float pitchInertia() {
        return pitchInertia;
    }

    public float landingImpulse() {
        return landingImpulse;
    }

    public float actionImpulse() {
        return actionImpulse;
    }

    public int airborneTicks() {
        return airborneTicks;
    }

    public String movementMode() {
        return movementMode;
    }

    public ItemMotionProfile itemProfile() {
        return itemProfile;
    }

    private static float wrapUnit(float value) {
        value %= 1.0f;
        return value < 0.0f ? value + 1.0f : value;
    }

    private static float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) {
            value -= 360.0f;
        }
        if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
