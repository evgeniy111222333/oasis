package ua.rp.chat.carry;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A bounded search; callers validate support, collision, protection and reachability. */
public final class CarryRecovery {
    private CarryRecovery() { }
    public static Vec3 find(Vec3 player, float yaw, Predicate<Vec3> safe) {
        double radians = Math.toRadians(yaw);
        Vec3 front = player.add(-Math.sin(radians) * 1.1, .006, Math.cos(radians) * 1.1);
        if (safe.test(front)) return front;
        BlockPos origin = BlockPos.containing(player);
        for (int radius = 1; radius <= 2; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    Vec3 candidate = Vec3.atBottomCenterOf(origin.offset(dx, 0, dz)).add(0, .006, 0);
                    if (safe.test(candidate)) return candidate;
                }
            }
        }
        throw new IllegalStateException("Рядом нет безопасного свободного места.");
    }
}
