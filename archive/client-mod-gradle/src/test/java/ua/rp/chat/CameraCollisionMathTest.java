package ua.rp.chat;

import java.util.List;

public final class CameraCollisionMathTest {
    private static final double EPSILON = 0.00002;
    private static final CameraCollisionMath.Point HALF_EXTENTS = point(0.05, 0.05, 0.05);
    private static final double CLEARANCE = 0.015;

    public static void main(String[] args) {
        CameraCollisionMath.Box wall = box(-4.0, -4.0, 1.0, 4.0, 4.0, 2.0);

        CameraCollisionMath.SweepResult open = sweep(
                point(0.0, 0.0, 0.0), point(0.54, 0.0, 0.0), List.of());
        assertClose("unobstructed offset is unchanged", 1.0, open.fraction());
        assertFalse("unobstructed origin is safe", open.startBlocked());

        CameraCollisionMath.SweepResult standing = sweep(
                point(0.0, 0.0, 0.70), point(0.0, 0.0, 0.20), List.of(wall));
        assertClose("normal 0.20 offset remains unchanged at the wall", 1.0, standing.fraction());

        CameraCollisionMath.SweepResult crouching = sweep(
                point(0.0, 0.0, 0.70), point(0.0, 0.0, 0.59), List.of(wall));
        assertTrue("crouch offset is clamped", crouching.fraction() > 0.0 && crouching.fraction() < 1.0);
        double crouchZ = 0.70 + 0.59 * crouching.fraction();
        assertTrue("near plane and clearance remain before the wall", crouchZ + 0.05 + CLEARANCE <= 1.0);

        CameraCollisionMath.Point desiredCamera = point(0.0, -0.04, 0.59);
        CameraCollisionMath.Point resolvedCamera = desiredCamera.scale(crouching.fraction());
        CameraCollisionMath.Point bodyCompensation = CameraCollisionMath.bodyCompensation(
                desiredCamera, resolvedCamera);
        CameraCollisionMath.Point body = point(0.0, -1.20, 0.0);
        assertClose("collision compensation preserves camera-relative body X",
                body.x() - desiredCamera.x(),
                body.x() + bodyCompensation.x() - resolvedCamera.x());
        assertClose("collision compensation preserves camera-relative body Y",
                body.y() - desiredCamera.y(),
                body.y() + bodyCompensation.y() - resolvedCamera.y());
        assertClose("collision compensation preserves camera-relative body Z",
                body.z() - desiredCamera.z(),
                body.z() + bodyCompensation.z() - resolvedCamera.z());

        CameraCollisionMath.Box thinPanel = box(-1.0, -1.0, 1.0, 1.0, 1.0, 1.0625);
        CameraCollisionMath.SweepResult tunnelling = sweep(
                point(0.0, 0.0, 0.0), point(0.0, 0.0, 2.0), List.of(thinPanel));
        assertTrue("continuous sweep catches a thin obstacle even when endpoint passed it",
                tunnelling.fraction() > 0.0 && tunnelling.fraction() < 0.5);

        CameraCollisionMath.Box sideWall = box(1.0, -4.0, -4.0, 2.0, 4.0, 4.0);
        CameraCollisionMath.SweepResult corner = sweep(
                point(0.70, 0.0, 0.70), point(0.59, 0.0, 0.59), List.of(wall, sideWall));
        assertTrue("diagonal corner clamps both-axis movement at the earliest surface",
                corner.fraction() > 0.0 && corner.fraction() < 0.5);
        assertTrue("both corner obstacles were evaluated", corner.testedObstacles() == 2);

        CameraCollisionMath.Box slab = box(-2.0, 0.0, -2.0, 2.0, 1.0, 2.0);
        CameraCollisionMath.SweepResult downward = CameraCollisionMath.sweep(
                point(0.0, 1.50, 0.0), point(0.0, -0.60, 0.0), point(0.10, 0.10, 0.10),
                CLEARANCE, List.of(slab));
        assertTrue("vertical crouch or kneel offset cannot enter a slab",
                downward.fraction() > 0.0 && downward.fraction() < 1.0);
        double finalY = 1.50 - 0.60 * downward.fraction();
        assertTrue("camera volume stays above slab", finalY - 0.10 - CLEARANCE >= 1.0);

        CameraCollisionMath.SweepResult blockedOrigin = sweep(
                point(0.0, 0.0, 0.96), point(0.0, 0.0, 0.20), List.of(wall));
        assertTrue("origin inside protected wall volume triggers fail closed", blockedOrigin.startBlocked());
        assertClose("blocked origin permits no custom travel", 0.0, blockedOrigin.fraction());

        CameraCollisionMath.SweepResult movingAway = sweep(
                point(0.0, 0.0, 0.935), point(0.0, 0.0, -0.20), List.of(wall));
        assertFalse("surface contact moving away is not treated as embedded", movingAway.startBlocked());
        assertClose("camera may always retreat from a surface", 1.0, movingAway.fraction());

        assertThrows("non-finite movement fails closed at the API boundary", () -> CameraCollisionMath.sweep(
                point(0.0, 0.0, 0.0), point(Double.NaN, 0.0, 0.0), HALF_EXTENTS,
                CLEARANCE, List.of(wall)));
        assertThrows("non-finite compensation fails closed at the API boundary",
                () -> CameraCollisionMath.bodyCompensation(
                        point(0.0, 0.0, 0.0), point(Double.POSITIVE_INFINITY, 0.0, 0.0)));
        System.out.println("CameraCollisionMathTest: all continuous collision and fail-closed invariants passed");
    }

    private static CameraCollisionMath.SweepResult sweep(
            CameraCollisionMath.Point start,
            CameraCollisionMath.Point movement,
            List<CameraCollisionMath.Box> obstacles) {
        return CameraCollisionMath.sweep(start, movement, HALF_EXTENTS, CLEARANCE, obstacles);
    }

    private static CameraCollisionMath.Point point(double x, double y, double z) {
        return new CameraCollisionMath.Point(x, y, z);
    }

    private static CameraCollisionMath.Box box(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new CameraCollisionMath.Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void assertClose(String name, double expected, double actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) {
            throw new AssertionError(name);
        }
    }

    private static void assertFalse(String name, boolean value) {
        assertTrue(name, !value);
    }

    private static void assertThrows(String name, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(name + ": expected IllegalArgumentException");
    }
}
