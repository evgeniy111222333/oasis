package ua.rp.chat.client.camera;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.CameraCollisionMath;

/** Resolves the custom first-person offset without allowing the camera volume through block collision shapes. */
public final class CameraCollisionResolver {
    public static final double SURFACE_CLEARANCE = 0.015;
    public static final Vec3 FALLBACK_HALF_EXTENTS = new Vec3(0.10, 0.10, 0.10);

    public Resolution resolve(Player player, Vec3 origin, Vec3 desiredOffset, Vec3 halfExtents) {
        if (player == null || origin == null || desiredOffset == null || halfExtents == null
                || !isFinite(origin) || !isFinite(desiredOffset) || !validExtents(halfExtents)) {
            return Resolution.blockedFallback();
        }
        if (desiredOffset.lengthSqr() <= 1.0e-12) {
            return new Resolution(Vec3.ZERO, 1.0, false, 0);
        }

        Level level = player.level();
        AABB cameraBox = AABB.ofSize(origin,
                halfExtents.x * 2.0,
                halfExtents.y * 2.0,
                halfExtents.z * 2.0);
        AABB sweepBounds = cameraBox.expandTowards(desiredOffset).inflate(SURFACE_CLEARANCE);
        if (!allCornersLoaded(level, sweepBounds)) {
            return Resolution.blockedFallback();
        }

        try {
            List<CameraCollisionMath.Box> obstacles = new ArrayList<>();
            for (VoxelShape shape : level.getBlockCollisions(player, sweepBounds)) {
                for (AABB box : shape.toAabbs()) {
                    obstacles.add(toBox(box));
                }
            }
            CameraCollisionMath.SweepResult sweep = CameraCollisionMath.sweep(
                    toPoint(origin),
                    toPoint(desiredOffset),
                    toPoint(halfExtents),
                    SURFACE_CLEARANCE,
                    obstacles);
            Vec3 resolved = desiredOffset.scale(sweep.fraction());
            return new Resolution(resolved, sweep.fraction(), sweep.startBlocked(), sweep.testedObstacles());
        } catch (RuntimeException exception) {
            return Resolution.blockedFallback();
        }
    }

    private static boolean allCornersLoaded(Level level, AABB box) {
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    if (!level.isLoaded(BlockPos.containing(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean validExtents(Vec3 extents) {
        return isFinite(extents) && extents.x >= 0.0 && extents.y >= 0.0 && extents.z >= 0.0;
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static CameraCollisionMath.Point toPoint(Vec3 value) {
        return new CameraCollisionMath.Point(value.x, value.y, value.z);
    }

    private static CameraCollisionMath.Box toBox(AABB box) {
        return new CameraCollisionMath.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public record Resolution(Vec3 offset, double fraction, boolean failClosed, int testedObstacles) {
        public static Resolution blockedFallback() {
            return new Resolution(Vec3.ZERO, 0.0, true, 0);
        }
    }
}
