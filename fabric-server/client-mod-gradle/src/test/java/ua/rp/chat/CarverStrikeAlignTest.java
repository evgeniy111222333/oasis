package ua.rp.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards the Strike Alignment System: plan/contact parity with the legacy aim,
 * normal-locked hammer trajectory, ring-stand preference and solve budgets.
 * Pure JVM, no Minecraft bootstrap.
 */
public final class CarverStrikeAlignTest {
    public static void main(String[] args) {
        verifyParityWithWorkAim();
        verifyEccentricContact();
        verifyNormalFacesPlayer();
        verifyTrajectoryLandsOnContact();
        verifyImpactSnap();
        verifyRingPrefersNormalSide();
        verifyRingWall();
        verifyRingPit();
        verifyRingSealed();
        verifyRingSqueeze();
        verifyCeilingPenalty();
        verifyImpactFront();
        verifyLodBands();
        verifyPoseLerp();
        verifySettleLogic();
        verifyWorkStance();
        verifyBenchmark();
        System.out.println("CarverStrikeAlignTest passed");
    }

    private static List<Integer> slabTop() {
        List<Integer> cells = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.add(x | (z << 4) | (15 << 8));
            }
        }
        return cells;
    }

    private static List<Integer> wallX() {
        List<Integer> cells = new ArrayList<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                cells.add(15 | (z << 4) | (y << 8));
            }
        }
        return cells;
    }

    private static void verifyParityWithWorkAim() {
        ua.rp.chat.carver.DraftMask mask = new ua.rp.chat.carver.DraftMask();
        for (int cell : slabTop()) mask.set(cell);
        double[] legacyContact = ua.rp.chat.carver.CarverWorkAim.contactWorld(
                new net.minecraft.core.BlockPos(10, 64, 20), mask);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(10, 64, 20, slabTop(), 12.0, 64.0, 22.0);
        require(close(plan.contactX(), legacyContact[0])
                        && close(plan.contactY(), legacyContact[1])
                        && close(plan.contactZ(), legacyContact[2]),
                "StrikePlan contact must match CarverWorkAim, got " + plan);
        require(plan.axis() == ua.rp.chat.carver.CarverWorkAim.faceNormalAxis(mask),
                "StrikePlan axis must match CarverWorkAim");
    }

    private static void verifyEccentricContact() {
        List<Integer> corner = new ArrayList<>();
        corner.add(0 | (0 << 4) | (15 << 8));
        corner.add(1 | (0 << 4) | (15 << 8));
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, corner, 5.0, 64.0, 5.0);
        // Corner cells x=0..1: centroid x in cells = 1.0 -> world x = 0.0625.
        require(plan.contactX() < 0.2 && plan.contactZ() < 0.2,
                "Eccentric draft must pull contact to the corner, got " + plan);
    }

    private static void verifyNormalFacesPlayer() {
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan east =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, wallX(), 5.0, 64.0, 0.5);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan west =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, wallX(), -5.0, 64.0, 0.5);
        require(east.normalX() > 0.9 && west.normalX() < -0.9,
                "X-face normal must flip to the player side");
        require(east.axis() == 0 && west.axis() == 0, "Wall must read as X axis");
    }

    private static void verifyTrajectoryLandsOnContact() {
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), 2.0, 64.0, 2.0);
        // Shaft passes through contact at every lift: distance from butt to the
        // contact-normal line must be ~0 (collinear by construction).
        for (double lift : new double[]{0.0, 0.5, 1.0}) {
            double[] butt = ua.rp.chat.carver.CarverTrajectory.buttPoint(
                    plan.contactX(), plan.contactY(), plan.contactZ(),
                    plan.normalX(), plan.normalY(), plan.normalZ(),
                    0.3, 0.0, 0.3, lift);
            double[] toButt = new double[]{butt[0] - plan.contactX(),
                    butt[1] - plan.contactY() - lift * ua.rp.chat.carver.CarverTrajectory.LIFT_HEIGHT,
                    butt[2] - plan.contactZ()};
            double along = toButt[0] * plan.normalX() + toButt[1] * plan.normalY() + toButt[2] * plan.normalZ();
            double perpSq = toButt[0] * toButt[0] + toButt[1] * toButt[1] + toButt[2] * toButt[2]
                    - along * along;
            require(perpSq < 0.09,
                    "Butt must ride the normal shaft, perpSq=" + perpSq + " lift=" + lift);
        }
    }

    private static void verifyImpactSnap() {
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), 2.0, 64.0, 2.0);
        double[] impact = ua.rp.chat.carver.CarverTrajectory.impactPoint(
                plan.contactX(), plan.contactY(), plan.contactZ(),
                plan.normalX(), plan.normalY(), plan.normalZ());
        double miss = ua.rp.chat.carver.CarverTrajectory.missDistance(
                impact, new double[]{plan.contactX(), plan.contactY(), plan.contactZ()});
        require(miss <= 0.021, "Impact butt must touch the contact, miss=" + miss);
    }

    private static void verifyRingPrefersNormalSide() {
        net.minecraft.core.BlockPos focus = new net.minecraft.core.BlockPos(0, 64, 0);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, wallX(), 5.0, 64.0, 0.5);
        // Flat open ground probe: every ring candidate is walkable.
        ua.rp.chat.client.carver.CarverAutoWalk.Solidity flat = new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
            @Override
            public boolean solid(int x, int y, int z) {
                return y <= 63;
            }

            @Override
            public boolean free(int x, int y, int z) {
                return y > 63;
            }
        };
        net.minecraft.world.phys.Vec3 stand = ua.rp.chat.client.carver.CarverAutoWalk.pickRingStand(
                5.0, 64.0, 0.5, -90.0f, focus, plan, flat);
        require(stand != null, "Ring must offer a stand on flat ground");
        // Wall at x=15 face, normal +X towards player east: stand must be east (x > 0).
        require(stand.x > 0.5, "Ring must prefer the normal side, got " + stand);
    }

    private static ua.rp.chat.client.carver.CarverAutoWalk.Solidity flat() {
        return new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
            @Override
            public boolean solid(int x, int y, int z) {
                return y <= 63;
            }

            @Override
            public boolean free(int x, int y, int z) {
                return y > 63;
            }
        };
    }

    private static void verifyRingWall() {
        net.minecraft.core.BlockPos focus = new net.minecraft.core.BlockPos(0, 64, 0);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), -5.0, 64.0, 0.5);
        final java.util.Set<String> walls = new java.util.HashSet<>();
        // Tall wall: blocks feet, head and any perch on top within the scan band.
        walls.add("-1,64,0");
        walls.add("-1,65,0");
        walls.add("-1,66,0");
        walls.add("-1,67,0");
        ua.rp.chat.client.carver.CarverAutoWalk.Solidity walled =
                new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
                    @Override
                    public boolean solid(int x, int y, int z) {
                        if (walls.contains(x + "," + y + "," + z)) return true;
                        return y <= 63;
                    }

                    @Override
                    public boolean free(int x, int y, int z) {
                        if (walls.contains(x + "," + y + "," + z)) return false;
                        return y > 63;
                    }
                };
        net.minecraft.world.phys.Vec3 stand = ua.rp.chat.client.carver.CarverAutoWalk.pickRingStand(
                -5.0, 64.0, 0.5, 90.0f, focus, plan, walled);
        require(stand != null, "Walled side must fall back to another side");
        require(!(Math.abs(stand.x + 0.5) < 1.0e-9 && Math.abs(stand.z - 0.5) < 1.0e-9),
                "Occupied west column must not be picked, got " + stand);
    }

    private static void verifyRingPit() {
        net.minecraft.core.BlockPos focus = new net.minecraft.core.BlockPos(0, 64, 0);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), 5.0, 64.0, 0.5);
        ua.rp.chat.client.carver.CarverAutoWalk.Solidity dug =
                new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
                    @Override
                    public boolean solid(int x, int y, int z) {
                        if (x == 1 && z == 0) return false;
                        return y <= 63;
                    }

                    @Override
                    public boolean free(int x, int y, int z) {
                        if (x == 1 && z == 0) return true;
                        return y > 63;
                    }
                };
        net.minecraft.world.phys.Vec3 stand = ua.rp.chat.client.carver.CarverAutoWalk.pickRingStand(
                5.0, 64.0, 0.5, -90.0f, focus, plan, dug);
        require(stand != null, "Pit side must fall back to another side");
        require(!(Math.abs(stand.x - 1.5) < 1.0e-9 && Math.abs(stand.z - 0.5) < 1.0e-9),
                "Groundless column must not be picked, got " + stand);
    }

    private static void verifyRingSealed() {
        net.minecraft.core.BlockPos focus = new net.minecraft.core.BlockPos(0, 64, 0);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), 5.0, 64.0, 0.5);
        ua.rp.chat.client.carver.CarverAutoWalk.Solidity sealed =
                new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
                    @Override
                    public boolean solid(int x, int y, int z) {
                        return true;
                    }

                    @Override
                    public boolean free(int x, int y, int z) {
                        return false;
                    }
                };
        require(ua.rp.chat.client.carver.CarverAutoWalk.pickRingStand(
                        5.0, 64.0, 0.5, -90.0f, focus, plan, sealed) == null,
                "Sealed focus must report no ring stand");
    }

    private static void verifyRingSqueeze() {
        net.minecraft.core.BlockPos focus = new net.minecraft.core.BlockPos(0, 64, 0);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), 5.0, 64.0, 0.5);
        // Chest-level walls everywhere except the four head columns: every ring
        // candidate keeps feet/head but loses the shoulder frame.
        final java.util.Set<String> heads = new java.util.HashSet<>();
        heads.add("1,65,0");
        heads.add("-1,65,0");
        heads.add("0,65,1");
        heads.add("0,65,-1");
        ua.rp.chat.client.carver.CarverAutoWalk.Solidity squeezed =
                new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
                    // Chest-level walls block shoulders but never count as
                    // standable ground: only the true floor is solid here.
                    @Override
                    public boolean solid(int x, int y, int z) {
                        return y <= 63;
                    }

                    @Override
                    public boolean free(int x, int y, int z) {
                        if (y == 65 && !heads.contains(x + "," + y + "," + z)) return false;
                        return y > 63;
                    }
                };
        require(ua.rp.chat.client.carver.CarverAutoWalk.pickRingStand(
                        5.0, 64.0, 0.5, -90.0f, focus, plan, squeezed) == null,
                "Squeezed ring must reject shoulder-less stands");
        require(ua.rp.chat.client.carver.CarverAutoWalk.shoulderClear(flat(), 1, 64, 0),
                "Open shoulders must pass on flat ground");
        require(!ua.rp.chat.client.carver.CarverAutoWalk.shoulderClear(squeezed, 1, 64, 0),
                "Walled shoulders must fail the frame check");
    }

    private static void verifyCeilingPenalty() {
        net.minecraft.core.BlockPos focus = new net.minecraft.core.BlockPos(0, 64, 0);
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, slabTop(), 0.5, 64.0, 4.5);
        final java.util.Set<String> blocked = new java.util.HashSet<>();
        // Tall south wall forces the contest east-vs-west (no perch on top);
        // low ceiling covers the east half swing room.
        blocked.add("0,64,1");
        blocked.add("0,65,1");
        blocked.add("0,66,1");
        blocked.add("0,67,1");
        for (int x = 1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) blocked.add(x + ",66," + z);
        }
        ua.rp.chat.client.carver.CarverAutoWalk.Solidity lowEast =
                new ua.rp.chat.client.carver.CarverAutoWalk.Solidity() {
                    @Override
                    public boolean solid(int x, int y, int z) {
                        if (blocked.contains(x + "," + y + "," + z)) return true;
                        return y <= 63;
                    }

                    @Override
                    public boolean free(int x, int y, int z) {
                        if (blocked.contains(x + "," + y + "," + z)) return false;
                        return y > 63;
                    }
                };
        net.minecraft.world.phys.Vec3 stand = ua.rp.chat.client.carver.CarverAutoWalk.pickRingStand(
                0.5, 64.0, 4.5, 0.0f, focus, plan, lowEast);
        require(stand != null, "Ceiling side must fall back, not fail");
        require(stand.x < 0.5,
                "Low swing ceiling must lose to the open side, got " + stand);
    }

    private static void verifyImpactFront() {
        require(ua.rp.chat.client.carver.CarverImpactFx.shouldFire(0.5, 0.95),
                "Rising front must fire");
        require(!ua.rp.chat.client.carver.CarverImpactFx.shouldFire(0.95, 0.99),
                "Held contact must not refire");
        require(!ua.rp.chat.client.carver.CarverImpactFx.shouldFire(0.0, 0.0),
                "Idle must not fire");
        require(!ua.rp.chat.client.carver.CarverImpactFx.shouldFire(0.95, 0.5),
                "Falling edge must not fire");
        require(ua.rp.chat.client.carver.CarverImpactFx.shouldFire(0.5, 0.5 + 0.4),
                "Next strike rise must fire again");
    }

    private static void verifyLodBands() {
        require(ua.rp.chat.client.carver.CarverWorkPoseCache.lodFor(-1.0)
                        == ua.rp.chat.client.carver.CarverWorkPoseCache.Lod.NEAR,
                "Unknown distance must read NEAR");
        require(ua.rp.chat.client.carver.CarverWorkPoseCache.lodFor(0.0)
                        == ua.rp.chat.client.carver.CarverWorkPoseCache.Lod.NEAR,
                "Close artisan must read NEAR");
        require(ua.rp.chat.client.carver.CarverWorkPoseCache.lodFor(144.0)
                        == ua.rp.chat.client.carver.CarverWorkPoseCache.Lod.NEAR,
                "12-block boundary must stay NEAR");
        require(ua.rp.chat.client.carver.CarverWorkPoseCache.lodFor(145.0)
                        == ua.rp.chat.client.carver.CarverWorkPoseCache.Lod.MID,
                "Past 12 blocks must read MID");
        require(ua.rp.chat.client.carver.CarverWorkPoseCache.lodFor(1024.0)
                        == ua.rp.chat.client.carver.CarverWorkPoseCache.Lod.MID,
                "32-block boundary must stay MID");
        require(ua.rp.chat.client.carver.CarverWorkPoseCache.lodFor(1025.0)
                        == ua.rp.chat.client.carver.CarverWorkPoseCache.Lod.FAR,
                "Past 32 blocks must read FAR");
    }

    private static void verifyPoseLerp() {
        ua.rp.chat.client.carver.CarverWorkPoseCache.Pose a =
                new ua.rp.chat.client.carver.CarverWorkPoseCache.Pose(
                        new double[]{0.0, 0.0, 0.0}, new double[]{0.0, 0.0, 0.0},
                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0, 0.0);
        ua.rp.chat.client.carver.CarverWorkPoseCache.Pose b =
                new ua.rp.chat.client.carver.CarverWorkPoseCache.Pose(
                        new double[]{2.0, 4.0, 6.0}, new double[]{1.0, 1.0, 1.0},
                        1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 1.0, 1.0);
        ua.rp.chat.client.carver.CarverWorkPoseCache.Pose start =
                ua.rp.chat.client.carver.CarverWorkPoseCache.lerp(a, b, 0.0f);
        ua.rp.chat.client.carver.CarverWorkPoseCache.Pose end =
                ua.rp.chat.client.carver.CarverWorkPoseCache.lerp(a, b, 1.0f);
        ua.rp.chat.client.carver.CarverWorkPoseCache.Pose mid =
                ua.rp.chat.client.carver.CarverWorkPoseCache.lerp(a, b, 0.5f);
        require(start == a, "t=0 must return the previous snapshot");
        require(end == b, "t=1 must return the current snapshot");
        require(close(mid.contact()[0], 1.0) && close(mid.contact()[1], 2.0)
                        && close(mid.leftYaw(), 0.5f) && close(mid.lift(), 0.5),
                "t=0.5 must split snapshots evenly");
    }

    private static void verifySettleLogic() {
        // Far from the stand: always SEEK, whatever the yaw error or budget.
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.5, 0.0f, 0, false)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.SEEK,
                "Far stand must SEEK");
        require(ua.rp.chat.carver.CarverSettleLogic.next(5.0, 170.0f, 999, true)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.SEEK,
                "Far stand must SEEK even past the budget");
        // Close and aligned: approve without any snap.
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.10, 1.0f, 3, true)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.APPROVE_ALIGNED,
                "Close and aligned must APPROVE_ALIGNED");
        // Close but turned away: ALIGN until the budget, then snap-approve.
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.10, 45.0f, 3, true)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.ALIGN,
                "Close but turned must ALIGN");
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.10, 45.0f, 10, true)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.APPROVE_TIMEOUT,
                "Spent budget must APPROVE_TIMEOUT");
        // Hysteresis: 0.2 engages only with the latch held, never fresh.
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.20, 45.0f, 3, false)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.SEEK,
                "Boundary without latch must SEEK");
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.20, 45.0f, 3, true)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.ALIGN,
                "Boundary with latch must stay ALIGN");
        require(ua.rp.chat.carver.CarverSettleLogic.next(0.35, 45.0f, 3, true)
                        == ua.rp.chat.carver.CarverSettleLogic.Action.SEEK,
                "Past release must return to SEEK");
        require(!ua.rp.chat.carver.CarverSettleLogic.latch(0.20, false)
                        && ua.rp.chat.carver.CarverSettleLogic.latch(0.20, true),
                "Latch must engage fresh only under 0.15 and hold under 0.30");
        // Yaw error wraps through 180 degrees.
        require(close(ua.rp.chat.carver.CarverSettleLogic.yawErr(179.0f, -179.0f), 2.0),
                "Yaw error must wrap, got "
                        + ua.rp.chat.carver.CarverSettleLogic.yawErr(179.0f, -179.0f));
        require(close(ua.rp.chat.carver.CarverSettleLogic.yawErr(10.0f, 10.0f), 0.0),
                "Equal yaws must read zero error");
        System.out.println("CarverSettleLogicTest: seek/align/approve/hysteresis passed");
    }

    private static void verifyWorkStance() {
        // Facing the contact dead-on: no torso turn needed.
        require(close(ua.rp.chat.carver.CarverWorkStance.bodyTurn(90.0, 90.0), 0.0),
                "Aligned body must not turn");
        // 20 degrees off: chest takes it all.
        require(close(ua.rp.chat.carver.CarverWorkStance.bodyTurn(110.0, 90.0),
                        Math.toRadians(20.0)),
                "Small offset must turn the chest fully");
        // 170 degrees off (the frozen-yaw case from the logs): clamped, neck covers rest.
        double clamped = ua.rp.chat.carver.CarverWorkStance.bodyTurn(90.0 + 170.0, 90.0);
        require(close(Math.abs(clamped), ua.rp.chat.carver.CarverWorkStance.MAX_BODY_TURN),
                "Huge offset must clamp, got " + clamped);
        // Wraps through 180 degrees.
        require(close(ua.rp.chat.carver.CarverWorkStance.bodyTurn(-179.0, 179.0),
                        Math.toRadians(2.0)),
                "Body turn must wrap");
        // Stance blend: zero outside work, staggered feet when settled.
        ua.rp.chat.carver.CarverWorkStance.LegStance zero =
                ua.rp.chat.carver.CarverWorkStance.blended(0.0);
        require(zero.leftPitch() == 0.0f && zero.rightPitch() == 0.0f
                        && zero.leftYaw() == 0.0f && zero.rightYaw() == 0.0f,
                "Zero blend must leave vanilla legs alone");
        ua.rp.chat.carver.CarverWorkStance.LegStance full =
                ua.rp.chat.carver.CarverWorkStance.blended(1.0);
        require(full.leftPitch() > 0.0f && full.rightPitch() < 0.0f
                        && full.leftYaw() < 0.0f && full.rightYaw() > 0.0f,
                "Full blend must stagger the feet, got " + full);
        System.out.println("CarverWorkStanceTest: torso turn and foot stance passed");
    }

    private static void verifyBenchmark() {
        List<Integer> cells = slabTop();
        long start = System.nanoTime();
        int iters = 2000;
        for (int i = 0; i < iters; i++) {
            ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, cells, 2.0, 64.0, 2.0);
        }
        double avgNs = (System.nanoTime() - start) / (double) iters;
        System.out.println("CarverStrikeAlignTest: solve avg=" + Math.round(avgNs) + "ns over " + iters);
        require(avgNs < 50_000.0, "Solve must stay under 50us, got " + avgNs + "ns");
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan =
                ua.rp.chat.carver.CarverStrikeAlign.solve(0, 64, 0, cells, 2.0, 64.0, 2.0);
        start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            ua.rp.chat.carver.CarverTrajectory.buttPoint(plan.contactX(), plan.contactY(), plan.contactZ(),
                    plan.normalX(), plan.normalY(), plan.normalZ(), 0.3, 0.0, 0.3, 0.7);
        }
        double trajNs = (System.nanoTime() - start) / 5000.0;
        System.out.println("CarverStrikeAlignTest: trajectory avg=" + Math.round(trajNs) + "ns over 5000");
        require(trajNs < 5_000.0, "Trajectory must stay under 5us, got " + trajNs + "ns");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("CarverStrikeAlignTest: " + message);
    }
}
