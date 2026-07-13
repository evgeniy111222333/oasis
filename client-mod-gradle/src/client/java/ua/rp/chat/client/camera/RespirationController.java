package ua.rp.chat.client.camera;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RespirationModel;
import ua.rp.chat.client.vitals.VitalsClientState;

/** Owns the one local respiratory phase consumed by every presentation layer. */
public final class RespirationController {
    private static final RespirationController INSTANCE = new RespirationController();
    private static final double TICK_SECONDS = 1.0 / 20.0;
    private static final long TICK_NANOS = 50_000_000L;

    private final RespirationModel model = new RespirationModel();
    private long lastTickNanos;
    private boolean exhalePending;

    private RespirationController() {
    }

    public static RespirationController getInstance() {
        return INSTANCE;
    }

    public void clientTick(LocalPlayer player) {
        if (player == null) {
            reset();
            return;
        }

        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double movement = clamp(horizontalSpeed / (player.isSprinting() ? 0.285 : 0.215), 0.0, 1.0);
        if (!player.onGround()) {
            movement *= 0.72;
        }
        if (player.isSpectator()) {
            movement = 0.0;
        }

        RespirationModel.Input input = new RespirationModel.Input(
                VitalsClientState.getStamina01(),
                VitalsClientState.getBreathDebt() / 100.0,
                VitalsClientState.getFatigue() / 100.0,
                1.0 - VitalsClientState.getBlood01(),
                VitalsClientState.getPain() / 100.0,
                movement,
                player.isSprinting(),
                VitalsClientState.isUnconscious());
        model.update(TICK_SECONDS, input);
        exhalePending = model.startedExhale();
        lastTickNanos = System.nanoTime();
    }

    public RespirationModel.Snapshot sampleFrame() {
        if (lastTickNanos == 0L) {
            return model.sample(1.0);
        }
        double partial = clamp((System.nanoTime() - lastTickNanos) / (double) TICK_NANOS, 0.0, 1.0);
        return model.sample(partial);
    }

    public RespirationModel.Snapshot sample(double partialTick) {
        return model.sample(partialTick);
    }

    public RespirationModel.Snapshot sampleRemote(float ageInTicks, int entityId) {
        double offset = wrap01(entityId * 0.6180339887498948);
        double phase = ageInTicks * (15.0 / 1200.0) + offset;
        return RespirationModel.snapshotForPhase(phase, 15.0, 0.08);
    }

    public boolean consumeExhaleStart() {
        boolean pending = exhalePending;
        exhalePending = false;
        return pending;
    }

    public void reset() {
        model.reset();
        exhalePending = false;
        lastTickNanos = 0L;
    }

    private static double wrap01(double value) {
        return value - Math.floor(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
