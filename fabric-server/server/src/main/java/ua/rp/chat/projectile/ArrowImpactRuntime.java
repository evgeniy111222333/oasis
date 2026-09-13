package ua.rp.chat.projectile;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the damage callback (where RP injury is resolved) with the surrounding
 * vanilla AbstractArrow#onHitEntity call on the same server tick.
 */
public final class ArrowImpactRuntime {
    private static final ConcurrentHashMap<UUID, ArrowImpactPhysics.Result> RESULTS =
            new ConcurrentHashMap<>();

    private ArrowImpactRuntime() {
    }

    public static void record(UUID projectileId, ArrowImpactPhysics.Result result) {
        if (projectileId != null && result != null) RESULTS.put(projectileId, result);
    }

    public static ArrowImpactPhysics.Result take(UUID projectileId) {
        return projectileId == null ? null : RESULTS.remove(projectileId);
    }
}
