package ua.rp.chat.combat;

import java.util.UUID;

final class CombatIntent {
    static final int PROTOCOL_VERSION = 1;

    private final UUID attackerId;
    private final UUID targetId;
    private final CombatBodyZone zone;
    private final double hitRatio;
    private final double lateral;
    private final double distance;
    private final long attackId;
    private final long receivedAt;

    CombatIntent(UUID attackerId, UUID targetId, CombatBodyZone zone, double hitRatio, double lateral,
                 double distance, long attackId, long receivedAt) {
        this.attackerId = attackerId;
        this.targetId = targetId;
        this.zone = zone;
        this.hitRatio = hitRatio;
        this.lateral = lateral;
        this.distance = distance;
        this.attackId = attackId;
        this.receivedAt = receivedAt;
    }

    UUID attackerId() {
        return attackerId;
    }

    UUID targetId() {
        return targetId;
    }

    CombatBodyZone zone() {
        return zone;
    }

    double hitRatio() {
        return hitRatio;
    }

    double lateral() {
        return lateral;
    }

    double distance() {
        return distance;
    }

    long attackId() {
        return attackId;
    }

    long receivedAt() {
        return receivedAt;
    }
}
