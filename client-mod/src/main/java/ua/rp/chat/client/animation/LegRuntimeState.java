package ua.rp.chat.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class LegRuntimeState {
    private boolean hasPosition;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean wasOnGround = true;
    private double horizontalSpeed;
    private float speed01;
    private float smoothedSpeed01;
    private float idlePhase;
    private float weightShiftPhase;
    private float landingCompression;
    private float landingVelocity;
    private float fallMemory;
    private int stillTicks;
    private int airborneTicks;
    private String movementMode = "idle";

    public void update(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            reset();
            return;
        }

        LocalPlayer player = client.player;
        updateMovement(player);
        updateLanding(player);
        updatePhases(player);
    }

    private void updateMovement(LocalPlayer player) {
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

        horizontalSpeed = moved;
        speed01 = clamp((float) (moved / 0.145), 0.0f, player.isSprinting() ? 1.45f : 1.05f);
        smoothedSpeed01 += (speed01 - smoothedSpeed01) * 0.20f;

        if (!player.onGround()) {
            movementMode = "airborne";
            stillTicks = 0;
            airborneTicks++;
            fallMemory = Math.max(fallMemory, (float) player.fallDistance);
            return;
        }

        airborneTicks = 0;

        if (player.isCrouching()) {
            movementMode = speed01 > 0.08f ? "crouch_walk" : "crouch_idle";
        } else if (player.isSprinting() && speed01 > 0.22f) {
            movementMode = "sprint";
        } else if (speed01 > 0.16f) {
            movementMode = "walk";
        } else {
            movementMode = "idle";
        }

        if (speed01 < 0.035f && player.onGround()) {
            stillTicks++;
        } else {
            stillTicks = 0;
        }
    }

    private void updateLanding(LocalPlayer player) {
        if (player.onGround() && !wasOnGround) {
            float impact = clamp(fallMemory / 4.8f, 0.0f, 1.0f);
            landingVelocity -= impact * 0.42f;
            landingCompression = Math.max(landingCompression, impact);
            fallMemory = 0.0f;
        }
        wasOnGround = player.onGround();

        float target = 0.0f;
        float force = (target - landingCompression) * 0.28f;
        landingVelocity += force;
        landingVelocity *= 0.70f;
        landingCompression = clamp(landingCompression + landingVelocity, 0.0f, 1.0f);
    }

    private void updatePhases(LocalPlayer player) {
        float movement = clamp(smoothedSpeed01, 0.0f, 1.0f);
        idlePhase = wrapUnit(idlePhase + 0.0048f + movement * 0.002f);
        float shiftRate = 0.0035f + movement * 0.040f + (player.isSprinting() ? 0.035f : 0.0f);
        weightShiftPhase = wrapUnit(weightShiftPhase + shiftRate);
    }

    public void reset() {
        hasPosition = false;
        horizontalSpeed = 0.0;
        speed01 = 0.0f;
        smoothedSpeed01 = 0.0f;
        idlePhase = 0.0f;
        weightShiftPhase = 0.0f;
        landingCompression = 0.0f;
        landingVelocity = 0.0f;
        fallMemory = 0.0f;
        stillTicks = 0;
        airborneTicks = 0;
        movementMode = "idle";
        wasOnGround = true;
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

    public float idlePhase() {
        return idlePhase;
    }

    public float weightShiftPhase() {
        return weightShiftPhase;
    }

    public float landingCompression() {
        return landingCompression;
    }

    public int stillTicks() {
        return stillTicks;
    }

    public int airborneTicks() {
        return airborneTicks;
    }

    public String movementMode() {
        return movementMode;
    }

    private static float wrapUnit(float value) {
        value %= 1.0f;
        return value < 0.0f ? value + 1.0f : value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
