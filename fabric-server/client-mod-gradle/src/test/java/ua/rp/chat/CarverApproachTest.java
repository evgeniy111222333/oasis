package ua.rp.chat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.client.carver.CarverAutoWalk;
import ua.rp.chat.client.carver.CarverDustParticle;
import ua.rp.chat.client.carver.CarverHologramRenderer;

import java.util.HashSet;
import java.util.Set;

/**
 * Guards the work-start approach: stand picking around obstacles, the dust storm
 * envelopes and the hologram shade ramp. Pure JVM, no Minecraft bootstrap.
 */
public final class CarverApproachTest {
    public static void main(String[] args) throws Exception {
        verifyStandOpenField();
        verifyStandAvoidsWall();
        verifyStandSkipsPit();
        verifyStandPrefersFacedSide();
        verifyStandNone();
        verifyDustEnvelopes();
        verifyShadeRamp();
        verifyTickLerp();
        verifyMeshCorners();
        verifyWorkStroke();
        verifyObservedWire();
        System.out.println("CarverApproachTest passed");
    }

    private static GridSolidity flat(int groundY) {
        return new GridSolidity(groundY);
    }

    /** Infinite flat ground at groundY, free air above. */
    private static final class GridSolidity implements CarverAutoWalk.Solidity {
        private final int groundY;
        private final Set<Long> walls = new HashSet<>();
        private final Set<Long> pits = new HashSet<>();

        GridSolidity(int groundY) {
            this.groundY = groundY;
        }

        GridSolidity wall(int x, int y, int z) {
            walls.add(key(x, y, z));
            return this;
        }

        GridSolidity pit(int x, int z) {
            pits.add(key(x, 0, z));
            return this;
        }

        private static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) y << 20) ^ z;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            if (walls.contains(key(x, y, z))) return true;
            if (pits.contains(key(x, 0, z))) return false;
            return y <= groundY;
        }

        @Override
        public boolean free(int x, int y, int z) {
            if (walls.contains(key(x, y, z))) return false;
            if (pits.contains(key(x, 0, z))) return true;
            return y > groundY;
        }
    }

    private static void verifyStandOpenField() {
        BlockPos focus = new BlockPos(0, 64, 0);
        Vec3 stand = CarverAutoWalk.pickStand(5.5, 64.0, 0.5, -90.0f, focus, flat(63));
        require(stand != null, "Open field must offer a stand");
        require(stand.x == 1.5 && stand.z == 0.5 && stand.y == 64.0,
                "Player east must stand east of the focus, got " + stand);
    }

    private static void verifyStandAvoidsWall() {
        BlockPos focus = new BlockPos(0, 64, 0);
        GridSolidity walled = flat(63).wall(1, 64, 0).wall(1, 65, 0);
        Vec3 stand = CarverAutoWalk.pickStand(5.5, 64.0, 0.5, -90.0f, focus, walled);
        require(stand != null, "Walled side must fall back to another side");
        require(!(stand.x == 1.5 && stand.z == 0.5),
                "Occupied east column must not be picked, got " + stand);
    }

    private static void verifyStandSkipsPit() {
        BlockPos focus = new BlockPos(0, 64, 0);
        GridSolidity dug = flat(63).pit(1, 0);
        Vec3 stand = CarverAutoWalk.pickStand(5.5, 64.0, 0.5, -90.0f, focus, dug);
        require(stand != null, "Pit side must fall back to another side");
        require(!(stand.x == 1.5 && stand.z == 0.5),
                "Groundless column must not be picked, got " + stand);
    }

    private static void verifyStandPrefersFacedSide() {
        BlockPos focus = new BlockPos(0, 64, 0);
        // Equidistant north and east, facing east (-90 yaw looks +X): east must win.
        Vec3 stand = CarverAutoWalk.pickStand(3.5, 64.0, 3.5, -90.0f, focus, flat(63));
        require(stand != null && stand.x == 1.5 && stand.z == 0.5,
                "Faced side must win ties, got " + stand);
    }

    private static void verifyStandNone() {
        BlockPos focus = new BlockPos(0, 64, 0);
        GridSolidity sealed = flat(63)
                .wall(1, 64, 0).wall(1, 65, 0).wall(1, 66, 0).wall(1, 67, 0).wall(1, 68, 0)
                .wall(-1, 64, 0).wall(-1, 65, 0).wall(-1, 66, 0).wall(-1, 67, 0).wall(-1, 68, 0)
                .wall(0, 64, 1).wall(0, 65, 1).wall(0, 66, 1).wall(0, 67, 1).wall(0, 68, 1)
                .wall(0, 64, -1).wall(0, 65, -1).wall(0, 66, -1).wall(0, 67, -1).wall(0, 68, -1)
                .pit(1, 0).pit(-1, 0).pit(0, 1).pit(0, -1);
        // Walls cover dy -2..+2 feet/head, pits kill the ground: nothing standable.
        require(CarverAutoWalk.pickStand(5.5, 64.0, 0.5, -90.0f, focus, sealed) == null,
                "Sealed focus must report no stand");
    }

    private static void verifyDustEnvelopes() {
        int life = 60;
        require(CarverDustParticle.sizeAt(0, life) < CarverDustParticle.sizeAt(4, life)
                        && CarverDustParticle.sizeAt(4, life) < CarverDustParticle.sizeAt(life, life),
                "Dust must grow through its life");
        require(CarverDustParticle.sizeAt(4, life) > 0.45f + 0.55f * 0.5f,
                "Dust must pop fast in the opening ticks");
        require(CarverDustParticle.alphaAt(0, life) == 0.0f
                        && CarverDustParticle.alphaAt(life, life) == 0.0f
                        && CarverDustParticle.alphaAt(10, life) > 0.5f,
                "Dust must snap in and dissolve out");
        System.out.println("CarverDustTest: envelopes passed");
    }

    private static void verifyShadeRamp() {
        int white = 0xFFFFFFFF;
        int up = CarverHologramRenderer.shade(white, Direction.UP);
        int down = CarverHologramRenderer.shade(white, Direction.DOWN);
        int north = CarverHologramRenderer.shade(white, Direction.NORTH);
        int east = CarverHologramRenderer.shade(white, Direction.EAST);
        require(up == white, "Top face must stay full bright");
        require((down & 0xFF) < (east & 0xFF) && (east & 0xFF) < (north & 0xFF)
                        && (north & 0xFF) < (up & 0xFF),
                "Shade must ramp down < east < north < up");
        System.out.println("CarverHologramShadeTest: ramp passed");
    }

    private static void verifyWorkStroke() {
        require(ua.rp.chat.carver.CarverWorkStroke.lift(0.0) == 0.0,
                "Stroke must start on the stone");
        double raised = ua.rp.chat.carver.CarverWorkStroke.lift(0.64);
        require(raised > 0.9, "Windup must carry the hammer up, got " + raised);
        double struck = ua.rp.chat.carver.CarverWorkStroke.lift(0.89);
        require(struck < 0.1, "Strike must land back down, got " + struck);
        double recoil = ua.rp.chat.carver.CarverWorkStroke.lift(0.95);
        require(recoil > 0.05 && recoil < 0.3, "Recoil must bounce off contact, got " + recoil);
        require(ua.rp.chat.carver.CarverWorkStroke.contact(0.5) == 0.0
                        && ua.rp.chat.carver.CarverWorkStroke.contact(0.95) > 0.9,
                "Contact must pulse only at the bottom");
        // Velocity concentrates in the strike quarter, not spread like a sine.
        double windupSpeed = (ua.rp.chat.carver.CarverWorkStroke.lift(0.33)
                - ua.rp.chat.carver.CarverWorkStroke.lift(0.32)) / 0.01;
        double strikeSpeed = (ua.rp.chat.carver.CarverWorkStroke.lift(0.87)
                - ua.rp.chat.carver.CarverWorkStroke.lift(0.88)) / 0.01;
        require(strikeSpeed > windupSpeed * 2.0,
                "Strike must fall several times faster than the windup rises");
        require(ua.rp.chat.carver.CarverWorkStroke.strikesFor(700) == 28
                        && ua.rp.chat.carver.CarverWorkStroke.strikesFor(10) == 3,
                "Strike count must pace long jobs and floor short ones");
        int first = ua.rp.chat.carver.CarverWorkStroke.strikeIndex(0.0, 700);
        int mid = ua.rp.chat.carver.CarverWorkStroke.strikeIndex(350.0, 700);
        int last = ua.rp.chat.carver.CarverWorkStroke.strikeIndex(700.0, 700);
        require(first == 0 && mid == 14 && last == 28,
                "Strike indices must step through the job");
        System.out.println("CarverWorkStrokeTest: snap and recoil passed");
    }

    private static void verifyObservedWire() throws Exception {
        java.util.UUID id = new java.util.UUID(0x123456789ABCDEFL, 0xFEDCBA9876543210L);
        byte[] start = ua.rp.chat.client.carver.CarverSyncPayload.observedStartData(
                id, 11, 64, -7, 700);
        java.io.DataInputStream in =
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(start));
        require(new java.util.UUID(in.readLong(), in.readLong()).equals(id)
                        && in.readInt() == 11 && in.readInt() == 64 && in.readInt() == -7
                        && in.readInt() == 700,
                "Observed start must round-trip id, focus and duration");
        byte[] end = ua.rp.chat.client.carver.CarverSyncPayload.observedEndData(id);
        java.io.DataInputStream endIn =
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(end));
        require(new java.util.UUID(endIn.readLong(), endIn.readLong()).equals(id),
                "Observed end must round-trip the id");
        System.out.println("CarverObservedWireTest: round-trip passed");
    }

    private static void verifyTickLerp() {
        require(ua.rp.chat.carver.CarverHologramMotion.lerpTick(0.0, 1.0, 0.5) == 0.5,
                "Half partial must split snapshots evenly");
        require(ua.rp.chat.carver.CarverHologramMotion.lerpTick(2.0, 4.0, 0.0) == 2.0
                        && ua.rp.chat.carver.CarverHologramMotion.lerpTick(2.0, 4.0, 1.0) == 4.0
                        && ua.rp.chat.carver.CarverHologramMotion.lerpTick(2.0, 4.0, -1.0) == 2.0
                        && ua.rp.chat.carver.CarverHologramMotion.lerpTick(2.0, 4.0, 2.0) == 4.0,
                "Partial must clamp to the snapshot range");
        require(ua.rp.chat.carver.CarverHologramMotion.renderPartial(0L) == 1.0,
                "Missing tick clock must render the latest snapshot");
        double before = ua.rp.chat.carver.CarverHologramMotion.lerpTick(0.0, 0.75, 0.25);
        double after = ua.rp.chat.carver.CarverHologramMotion.lerpTick(0.0, 0.75, 0.75);
        require(before < after && before > 0.0 && after < 0.75,
                "Lift interpolation must glide strictly inside the snapshots");
        System.out.println("CarverTickLerpTest: partials passed");
    }

    private static void verifyMeshCorners() {
        ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Face top =
                new ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Face(
                        ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Direction.UP, 1,
                        0, 15, 0, 16, 16, 16);
        float[][] corners = ua.rp.chat.client.carver.CarverHologramRenderer.faceCorners(top);
        require(corners.length == 4, "Faces must emit quads");
        for (float[] corner : corners) {
            require(corner[1] == 1.0f, "Top face must sit on y=1");
            require(corner[0] >= 0.0f && corner[0] <= 1.0f
                            && corner[2] >= 0.0f && corner[2] <= 1.0f,
                    "Corners must stay inside the unit cube");
        }
        require(corners[0][0] == 0.0f && corners[0][2] == 1.0f
                        && corners[2][0] == 1.0f && corners[2][2] == 0.0f,
                "Top winding must match the terrain emitter");
        System.out.println("CarverMeshCornersTest: winding passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("CarverApproachTest: " + message);
    }
}
