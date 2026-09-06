package ua.rp.chat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class CombatIntentSender {
    private static long nextAttackId = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE / 2L);

    private CombatIntentSender() {
    }

    public static void send(Player attacker, Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (!(attacker instanceof LocalPlayer) || attacker != client.player || target == null || client.level == null) {
            return;
        }
        try {
            if (!ClientPlayNetworking.canSend(CombatIntentPayload.TYPE)) {
                return;
            }
            HitInfo hit = hitInfo(attacker, target);
            ClientPlayNetworking.send(new CombatIntentPayload(
                    nextAttackId++,
                    target.getUUID(),
                    hit.zone.ordinal(),
                    hit.hitRatio,
                    hit.lateral,
                    attacker.distanceTo(target)
            ));
        } catch (RuntimeException ignored) {
            // The server can still fall back to its own weighted body-zone resolver.
        }
    }

    private static HitInfo hitInfo(Player attacker, Entity target) {
        AABB box = target.getBoundingBox();
        Vec3 eye = attacker.getEyePosition(1.0f);
        Vec3 look = attacker.getViewVector(1.0f).normalize();
        Vec3 end = eye.add(look.scale(Math.max(4.5, attacker.distanceTo(target) + 1.5)));
        Optional<Vec3> clipped = box.clip(eye, end);
        Vec3 hit = clipped.orElse(box.getCenter());

        double height = Math.max(0.1, box.maxY - box.minY);
        double hitRatio = clamp((hit.y - box.minY) / height, 0.0, 1.0);

        Vec3 center = box.getCenter();
        double targetYaw = Math.toRadians(target.getYRot());
        if (target instanceof net.minecraft.world.entity.LivingEntity living) {
            targetYaw = Math.toRadians(living.yBodyRot);
        }
        // Persist wound placement in victim-local UV space. Attacker-view space
        // made an existing mark jump when either player turned.
        Vec3 right = new Vec3(Math.cos(targetYaw), 0.0, Math.sin(targetYaw));
        if (right.lengthSqr() < 0.001) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }
        double halfWidth = Math.max(0.22, Math.max(box.maxX - box.minX, box.maxZ - box.minZ) * 0.5);
        double lateral = clamp(hit.subtract(center).dot(right) / halfWidth, -1.0, 1.0);

        return new HitInfo(zoneFor(hitRatio, lateral), hitRatio, lateral);
    }

    private static BodyZone zoneFor(double hitRatio, double lateral) {
        if (hitRatio >= 0.82) {
            return BodyZone.HEAD;
        }
        if (hitRatio >= 0.46) {
            if (Math.abs(lateral) > 0.34) {
                return lateral < 0.0 ? BodyZone.RIGHT_ARM : BodyZone.LEFT_ARM;
            }
            return BodyZone.TORSO;
        }
        if (hitRatio >= 0.32 && Math.abs(lateral) > 0.52) {
            return lateral < 0.0 ? BodyZone.RIGHT_ARM : BodyZone.LEFT_ARM;
        }
        return lateral < 0.0 ? BodyZone.RIGHT_LEG : BodyZone.LEFT_LEG;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HitInfo(BodyZone zone, double hitRatio, double lateral) {
    }

    private enum BodyZone {
        HEAD,
        TORSO,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG
    }
}
