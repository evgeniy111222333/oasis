package ua.rp.chat.vitals;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.client.blood.BloodFxPayload;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends compact wound semantics to nearby Fabric clients. It intentionally does
 * not simulate particles on the server.
 */
public final class BloodFxService {
    private static final double TRACKING_DISTANCE_SQ = 64.0 * 64.0;

    private final RPChat plugin;
    private final AtomicInteger revisions = new AtomicInteger();

    public BloodFxService(RPChat plugin) {
        this.plugin = plugin;
    }

    public void impact(ServerPlayer victim, long woundId, int zone, int face, int profile, float localSide, float localHeight,
                       float intensity, float bleeding, float impactVolumeMl, float flowMlPerSecond,
                       float remainingBloodMl, float penetrationDepth, Vec3 direction, long seed, int flags) {
        Vec3 safeDirection = normalize(direction);
        broadcast(victim, new BloodFxPayload(
                BloodFxPayload.IMPACT,
                victim.getId(),
                victim.getUUID(),
                woundId,
                zone,
                face,
                profile,
                clamp(localSide, -1.0f, 1.0f),
                clamp(localHeight, 0.0f, 1.0f),
                clamp(intensity, 0.0f, 1.0f),
                clamp(bleeding, 0.0f, 100.0f),
                clamp(impactVolumeMl, 0.0f, 40.0f),
                clamp(flowMlPerSecond, 0.0f, 20.0f),
                clamp(remainingBloodMl, 0.0f, 5_000.0f),
                (float) safeDirection.x,
                (float) safeDirection.y,
                (float) safeDirection.z,
                clamp(penetrationDepth, 0.0f, 0.75f),
                seed,
                nextRevision(),
                flags
        ));
    }

    public void syncWound(ServerPlayer victim, long woundId, int zone, int face, int profile, float localSide, float localHeight,
                          float intensity, float bleeding, float flowMlPerSecond, float remainingBloodMl,
                          float penetrationDepth, Vec3 direction, long seed, int flags) {
        broadcast(victim, woundPayload(victim, woundId, zone, face, profile, localSide, localHeight,
                intensity, bleeding, flowMlPerSecond, remainingBloodMl, penetrationDepth, direction, seed, flags));
    }

    public void syncWoundTo(ServerPlayer observer, ServerPlayer victim, long woundId, int zone, int face, int profile,
                            float localSide, float localHeight, float intensity, float bleeding,
                            float flowMlPerSecond, float remainingBloodMl,
                            float penetrationDepth, Vec3 direction, long seed, int flags) {
        if (!canObserve(observer, victim)) return;
        send(observer, woundPayload(victim, woundId, zone, face, profile, localSide, localHeight,
                intensity, bleeding, flowMlPerSecond, remainingBloodMl, penetrationDepth, direction, seed, flags));
    }

    public void clear(ServerPlayer victim, int zone) {
        broadcast(victim, new BloodFxPayload(
                BloodFxPayload.CLEAR,
                victim.getId(),
                victim.getUUID(),
                0L,
                zone,
                0,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0L,
                nextRevision(),
                0
        ));
    }

    private BloodFxPayload woundPayload(ServerPlayer victim, long woundId, int zone, int face, int profile,
                                        float localSide, float localHeight, float intensity,
                                        float bleeding, float flowMlPerSecond, float remainingBloodMl,
                                        float penetrationDepth, Vec3 direction, long seed, int flags) {
        Vec3 safeDirection = normalize(direction);
        return new BloodFxPayload(
                BloodFxPayload.WOUND_SYNC,
                victim.getId(),
                victim.getUUID(),
                woundId,
                zone,
                face,
                profile,
                clamp(localSide, -1.0f, 1.0f),
                clamp(localHeight, 0.0f, 1.0f),
                clamp(intensity, 0.0f, 1.0f),
                clamp(bleeding, 0.0f, 100.0f),
                0.0f,
                clamp(flowMlPerSecond, 0.0f, 20.0f),
                clamp(remainingBloodMl, 0.0f, 5_000.0f),
                (float) safeDirection.x,
                (float) safeDirection.y,
                (float) safeDirection.z,
                clamp(penetrationDepth, 0.0f, 0.75f),
                seed,
                nextRevision(),
                flags
        );
    }

    private void broadcast(ServerPlayer victim, BloodFxPayload payload) {
        if (victim == null || plugin.getServer() == null) return;
        for (ServerPlayer observer : plugin.getServer().getPlayerList().getPlayers()) {
            if (canObserve(observer, victim)) {
                send(observer, payload);
            }
        }
    }

    private boolean canObserve(ServerPlayer observer, ServerPlayer victim) {
        return observer != null
                && victim != null
                && observer.level() == victim.level()
                && observer.distanceToSqr(victim) <= TRACKING_DISTANCE_SQ;
    }

    private void send(ServerPlayer observer, BloodFxPayload payload) {
        if (ServerPlayNetworking.canSend(observer, BloodFxPayload.TYPE)) {
            ServerPlayNetworking.send(observer, payload);
        }
    }

    private int nextRevision() {
        return revisions.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }

    private static Vec3 normalize(Vec3 direction) {
        if (direction == null || !Double.isFinite(direction.x) || !Double.isFinite(direction.y)
                || !Double.isFinite(direction.z) || direction.lengthSqr() < 1.0e-6) {
            return new Vec3(0.0, 0.12, 1.0).normalize();
        }
        return direction.normalize();
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
