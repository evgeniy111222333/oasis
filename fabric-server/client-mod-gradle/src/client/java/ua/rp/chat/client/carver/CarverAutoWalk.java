package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Autopilot approach for the work start: on SPACE the artisan walks up tight to
 * the workpiece by itself instead of carving from across the room.
 *
 * <p>Steering rides the vanilla input layer (virtual forward/sprint/jump keys plus
 * body yaw), so physics, collisions, step-up, gravity and the server movement
 * validation all behave exactly like player-held keys. The walk aborts on fluids,
 * drops, timeout, screen close or any mouse press, and hands the keys back in every
 * exit path. Arrival sends the normal approve, so the fall, the storm and the work
 * simulation all run their usual course.</p>
 */
public final class CarverAutoWalk {
    /** Ticks before the walk gives up and hands control back. */
    static final int TIMEOUT_TICKS = 200;
    /** Arrival radius in blocks around the stand point. */
    static final double ARRIVE_RADIUS = 0.7;
    /** Sprint past this distance, stroll inside it for a soft landing. */
    static final double SPRINT_BEYOND = 3.5;

    private CarverAutoWalk() {
    }

    private static boolean active;
    private static Vec3 target;
    private static int ticks;
    // Settle tuning lives in CarverSettleLogic (single source of truth,
    // unit-tested); this class only executes its decisions.
    /**
     * Conservative design-leash margin for settle micro-steps. Server default
     * {@code designLeashBlocks} is 6.0; settle never steps past 5.0 from the
     * walk origin, so the dither can never cause a leash cancel by itself.
     */
    static final double SETTLE_LEASH_SAFE = 5.0;
    private static boolean settling;
    private static int settleTicks;
    private static float settleYaw;
    /** Hysteresis latch for the settle close band (see CarverSettleLogic). */
    private static boolean wasClose;
    /**
     * AIM cache: solved once in {@link #start}, reused by steering and settle.
     * The ring scan never runs per-tick; a mid-walk draft edit is picked up by
     * the next SPACE press, never by re-solving under the walker's feet.
     */
    private static ua.rp.chat.carver.CarverStrikeAlign.StrikePlan aimPlan;
    private static double walkStartX;
    private static double walkStartY;
    private static double walkStartZ;

    public static boolean active() {
        return active;
    }

    /**
     * Starts the approach: picks the stand cell hugging the focus and notifies the
     * server (it suspends the design leash while walking). Falls back to an
     * immediate approve when no stand exists, preserving the old behaviour.
     */
    public static void start() {
        abort();
        if (!CarverClientState.designing() || CarverClientState.focus() == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;
        if (CarverClientState.draft().isEmpty()) {
            CarverClientState.sendApprove();
            return;
        }
        BlockPos focus = CarverClientState.focus();
        Vec3 player = minecraft.player.position();
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan = null;
        Vec3 stand = null;
        try {
            plan = solvePlan(player.x, player.y, player.z, focus);
            if (plan != null) {
                stand = pickRingStand(player.x, player.y, player.z,
                        minecraft.player.getYRot(), focus, plan, levelProbe(minecraft));
                if (stand != null) settleYaw = plan.standYaw();
            }
        } catch (RuntimeException ignored) {
            plan = null;
            stand = null;
        }
        if (stand == null) {
            stand = pickStand(player.x, player.y, player.z,
                    minecraft.player.getYRot(), focus, levelProbe(minecraft));
        }
        if (stand == null) {
            trace("no stand found, approving immediately");
            CarverClientState.sendApprove();
            return;
        }
        aimPlan = plan;
        target = stand;
        ticks = 0;
        settling = false;
        settleTicks = 0;
        wasClose = false;
        walkStartX = player.x;
        walkStartY = player.y;
        walkStartZ = player.z;
        active = true;
        trace("walking to " + stand);
        CarverSasDiag.start(stand.x, stand.z,
                plan == null ? minecraft.player.getYRot() : plan.standYaw());
        CarverClientState.sendAutowalk();
    }

    /** Standability probe over absolute block positions. Pure: unit-testable with grids. */
    public interface Solidity {
        boolean solid(int x, int y, int z);

        boolean free(int x, int y, int z);
    }

    /**
     * Contact-first stand pick: solves the StrikePlan from the live draft, then scores
     * a ring of candidates around the contact along the face normal. Falls back to the
     * legacy four-side pick when the draft is empty or the ring is fully blocked.
     * Pure over the probe: unit-testable.
     */
    public static Vec3 pickStandAligned(double px, double py, double pz, float yawDeg,
                                        BlockPos focus, Solidity solid) {
        try {
            ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan = solvePlan(px, py, pz, focus);
            if (plan != null) {
                Vec3 ring = pickRingStand(px, py, pz, yawDeg, focus, plan, solid);
                if (ring != null) {
                    settleYaw = plan.standYaw();
                    return ring;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return pickStand(px, py, pz, yawDeg, focus, solid);
    }

    /** Solves the shared StrikePlan from the live client draft. Null-safe. */
    static ua.rp.chat.carver.CarverStrikeAlign.StrikePlan solvePlan(
            double px, double py, double pz, BlockPos focus) {
        if (focus == null) return null;
        java.util.List<Integer> cells;
        try {
            cells = CarverClientState.draft().cells();
        } catch (RuntimeException unreadable) {
            return null;
        }
        return ua.rp.chat.carver.CarverStrikeAlign.solve(
                focus.getX(), focus.getY(), focus.getZ(), cells, px, py, pz);
    }

    /** Minimum free chest-level neighbours for the shoulder frame. Pure. */
    static final int SHOULDER_MIN_FREE = 2;
    /** Score penalty for a blocked swing ceiling above the stand. Pure. */
    static final double LOW_CEILING_PENALTY = 1.5;

    /**
     * Ring candidates around the contact at ideal hammer reach, biased to the face
     * normal side. Each candidate needs free feet/head plus solid ground within two
     * blocks vertically, plus a shoulder frame (at least two free chest-level
     * neighbours: the workpiece itself always occupies one) so the model never clips
     * a wall mid-swing. A blocked swing ceiling above the stand deprioritizes the
     * candidate instead of rejecting it. Scores closeness, height gap, turn cost and
     * normal alignment so the artisan stands perpendicular to the carved face: the
     * shaft then flies straight and the hammer cannot miss sideways. Pure over the
     * probe.
     */
    public static Vec3 pickRingStand(double px, double py, double pz, float yawDeg,
                                     BlockPos focus,
                                     ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan,
                                     Solidity solid) {
        if (plan == null || solid == null) return null;
        // Ring geometry: circle around the focus center. For side faces the radius
        // follows the solved ideal offset; for top faces (near-vertical normal) the
        // ideal offset degenerates to the contact column, so a working radius is used
        // instead: the artisan stands beside the block leaning over it, never in it.
        double ix = plan.contactX() + plan.normalX() * ua.rp.chat.carver.CarverStrikeAlign.IDEAL_STAND_DIST;
        double iz = plan.contactZ() + plan.normalZ() * ua.rp.chat.carver.CarverStrikeAlign.IDEAL_STAND_DIST;
        double ox0 = ix - (focus.getX() + 0.5);
        double oz0 = iz - (focus.getZ() + 0.5);
        double radius = Math.sqrt(ox0 * ox0 + oz0 * oz0);
        double minRadius = ua.rp.chat.carver.CarverStrikeAlign.IDEAL_STAND_DIST * 0.8;
        if (!(radius >= minRadius)) radius = minRadius;
        double base = Math.atan2(oz0, ox0);
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (int step = 0; step < 8; step++) {
            double ang = base + step * Math.PI / 4.0;
            double sx = focus.getX() + 0.5 + Math.cos(ang) * radius;
            double sz = focus.getZ() + 0.5 + Math.sin(ang) * radius;
            int bx = (int) Math.floor(sx);
            int bz = (int) Math.floor(sz);
            for (int dy = -2; dy <= 2; dy++) {
                int fy = focus.getY() + dy;
                if (!safeFree(solid, bx, fy, bz) || !safeFree(solid, bx, fy + 1, bz)) continue;
                if (!safeSolid(solid, bx, fy - 1, bz)) continue;
                if (!shoulderClear(solid, bx, fy, bz)) continue;
                double dx = (bx + 0.5) - px;
                double dyGap = fy - py;
                double dz = (bz + 0.5) - pz;
                double score = dx * dx + dz * dz + 4.0 * dyGap * dyGap;
                if (!safeFree(solid, bx, fy + 2, bz)) score += LOW_CEILING_PENALTY;
                float faceYaw = (float) Math.toDegrees(Math.atan2(-(plan.contactX() - (bx + 0.5)),
                        plan.contactZ() - (bz + 0.5)));
                float turn = Math.abs(wrapDegrees(faceYaw - yawDeg));
                score += turn / 180.0;
                double dirX = (bx + 0.5) - plan.contactX();
                double dirZ = (bz + 0.5) - plan.contactZ();
                double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (dirLen > 1.0e-6) {
                    double along = -(dirX * plan.normalX() + dirZ * plan.normalZ()) / dirLen;
                    score += (1.0 - along) * 2.0;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = new Vec3(bx + 0.5, fy, bz + 0.5);
                }
            }
        }
        return best;
    }

    /**
     * Shoulder frame: at least {@link #SHOULDER_MIN_FREE} of the four chest-level
     * neighbours must be free. The workpiece column itself normally blocks one, so
     * a 1-wide corridor (two opposite walls) is rejected while an open side passes.
     * Pure over the probe.
     */
    public static boolean shoulderClear(Solidity solid, int bx, int fy, int bz) {
        int free = 0;
        if (safeFree(solid, bx + 1, fy + 1, bz)) free++;
        if (safeFree(solid, bx - 1, fy + 1, bz)) free++;
        if (safeFree(solid, bx, fy + 1, bz + 1)) free++;
        if (safeFree(solid, bx, fy + 1, bz - 1)) free++;
        return free >= SHOULDER_MIN_FREE;
    }

    /**
     * Stand cell hugging the focus: one of the four side columns with free feet and
     * head plus solid ground within two blocks up or down. Scores closeness to the
     * player, penalizes height gaps and rewards the side already faced, so the walk
     * turns as little as possible. Pure over the probe: unit-testable.
     * Legacy path: kept for empty drafts and as a fallback for the aligned pick.
     */
    public static Vec3 pickStand(double px, double py, double pz, float yawDeg, BlockPos focus,
                                 Solidity solid) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (int[] dir : dirs) {
            int nx = focus.getX() + dir[0];
            int nz = focus.getZ() + dir[1];
            for (int dy = -2; dy <= 2; dy++) {
                int fy = focus.getY() + dy;
                if (!safeFree(solid, nx, fy, nz) || !safeFree(solid, nx, fy + 1, nz)) continue;
                if (!safeSolid(solid, nx, fy - 1, nz)) continue;
                double sx = nx + 0.5;
                double sy = fy;
                double sz = nz + 0.5;
                double dx = sx - px;
                double dyGap = sy - py;
                double dz = sz - pz;
                double score = dx * dx + dz * dz + 4.0 * dyGap * dyGap;
                float faceYaw = (float) Math.toDegrees(Math.atan2(-dir[0], dir[1]));
                float turn = Math.abs(wrapDegrees(faceYaw - yawDeg));
                score += turn / 180.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Vec3(sx, sy, sz);
                }
            }
        }
        return best;
    }

    private static boolean safeFree(Solidity solid, int x, int y, int z) {
        try {
            return solid.free(x, y, z);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean safeSolid(Solidity solid, int x, int y, int z) {
        try {
            return solid.solid(x, y, z);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Level-backed probe: air reads free, opaque full cubes read solid. */
    static Solidity levelProbe(Minecraft minecraft) {
        return new Solidity() {
            @Override
            public boolean solid(int x, int y, int z) {
                try {
                    net.minecraft.world.level.block.state.BlockState state =
                            minecraft.level.getBlockState(new BlockPos(x, y, z));
                    return !state.isAir() && state.isSolidRender();
                } catch (RuntimeException unreadable) {
                    return true;
                }
            }

            @Override
            public boolean free(int x, int y, int z) {
                try {
                    return minecraft.level.getBlockState(new BlockPos(x, y, z)).isAir();
                } catch (RuntimeException unreadable) {
                    return false;
                }
            }
        };
    }

    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    /** START-tick steering: runs before vanilla polls movement keys. */
    public static void tickStart(Minecraft minecraft) {
        if (minecraft != null && minecraft.player != null
                && !active && CarverClientState.working()) {
            // Hands on the workpiece: locomotion keys stay down while the
            // simulation carves. Every exit path (done, close, cancel) runs
            // through the same key release below via abort paths.
            stopKeys(minecraft);
            minecraft.options.keyDown.setDown(false);
            minecraft.options.keyLeft.setDown(false);
            minecraft.options.keyRight.setDown(false);
            minecraft.options.keySprint.setDown(false);
        }
        if (minecraft != null && minecraft.player != null
                && !active && CarverClientState.designing()) {
            // The design screen eats SPACE for the work start, but vanilla already
            // latched the physical key into keyJump: without this the artisan hops
            // on every approval. Cleared here, before the movement poll; the walk
            // below re-arms it only while it genuinely needs a jump.
            minecraft.options.keyJump.setDown(false);
        }
        if (!active) return;
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            abort();
            return;
        }
        LocalPlayer player = minecraft.player;
        if (!CarverClientState.designing() || target == null) {
            abort();
            return;
        }
        if (player.isInWater() || player.isInLava()) {
            abortWith(minecraft, "Автопилот отменён: вода.");
            return;
        }
        if (player.fallDistance > 2.5f) {
            abortWith(minecraft, "Автопилот отменён: обрыв.");
            return;
        }
        CarverSasDiag.tick();
        if (ticks++ > TIMEOUT_TICKS) {
            // Walk stalled (blocked, keys ineffective, no path): start the work
            // from here anyway instead of dead-ending the SPACE press. The work
            // leash anchors fresh at approval, so distance stays legal. The body
            // is snapped onto the strike yaw first so the frozen work pose still
            // faces the contact even though the feet never made it.
            stopKeys(minecraft);
            active = false;
            settling = false;
            snapToStrikeYaw(player);
            CarverSasDiag.finish("walk-timeout", 0.0f, -1.0, ticks);
            aimPlan = null;
            CarverPerfLog.sasSettle(ticks, -1.0, -1.0);
            trace("timeout after " + ticks + " ticks, approving in place");
            if (minecraft.player != null) {
                minecraft.gui.setOverlayMessage(
                        Component.literal("Не дійшов — починаю тут."), false);
            }
            CarverClientState.sendApprove();
            return;
        }
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double dy = target.y - player.getY();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        if (distXZ < ARRIVE_RADIUS && Math.abs(dy) < 1.3) {
            // Settle phase, one yaw target per tick by construction (SEEK faces the
            // stand while stepping, ALIGN faces the strike yaw while standing): the
            // two targets can never fight inside one tick, which is what used to
            // visibly spin the character on arrival.
            if (!settling) {
                settling = true;
                settleTicks = 0;
                wasClose = false;
                if (aimPlan != null) settleYaw = aimPlan.standYaw();
                else settleYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                trace("arrived, settling yaw=" + settleYaw);
            }
            float yawErr = ua.rp.chat.carver.CarverSettleLogic.yawErr(player.getYRot(), settleYaw);
            ua.rp.chat.carver.CarverSettleLogic.Action action =
                    ua.rp.chat.carver.CarverSettleLogic.next(distXZ, yawErr, settleTicks, wasClose);
            wasClose = ua.rp.chat.carver.CarverSettleLogic.latch(distXZ, wasClose);
            float cur = player.getYRot();
            switch (action) {
                case SEEK -> {
                    // Too far to align: keep walking at the stand, facing it. Never
                    // touches the strike yaw, so no target fight is possible here.
                    // At the leash margin the dither stops and the walk approves in
                    // place instead of earning a server leash cancel by itself.
                    if (leashSafe(player)) {
                        float face = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        player.setYRot(cur + wrapDegrees(face - cur) * 0.35f);
                        minecraft.options.keyUp.setDown(true);
                        minecraft.options.keySprint.setDown(false);
                        minecraft.options.keyJump.setDown(false);
                        CarverSasDiag.sample(CarverSasDiag.SEEK,
                                player.getX(), player.getZ(), player.getYRot(), face, distXZ);
                    } else {
                        stopKeys(minecraft);
                        snapToStrikeYaw(player);
                        float err = ua.rp.chat.carver.CarverSettleLogic.yawErr(
                                player.getYRot(), settleYaw);
                        CarverSasDiag.finish("leash-margin", err, distXZ, settleTicks);
                        stopWalk();
                        CarverPerfLog.sasSettle(settleTicks, err, distXZ);
                        trace("settle leash margin, approving");
                        CarverClientState.sendApprove();
                    }
                }
                case ALIGN -> {
                    stopKeys(minecraft);
                    player.setYRot(cur + wrapDegrees(settleYaw - cur) * 0.35f);
                    CarverSasDiag.sample(CarverSasDiag.ALIGN,
                            player.getX(), player.getZ(), player.getYRot(), settleYaw, distXZ);
                }
                case APPROVE_ALIGNED -> {
                    stopKeys(minecraft);
                    float err = ua.rp.chat.carver.CarverSettleLogic.yawErr(
                            player.getYRot(), settleYaw);
                    CarverSasDiag.finish("aligned", err, distXZ, settleTicks);
                    stopWalk();
                    CarverPerfLog.sasSettle(settleTicks, err, distXZ);
                    trace("settled, approving");
                    CarverClientState.sendApprove();
                }
                case APPROVE_TIMEOUT -> {
                    // Budget spent with the body still turned away: snap onto the
                    // strike yaw (position untouched, leash unaffected) so the frozen
                    // work pose faces the contact instead of nowhere.
                    stopKeys(minecraft);
                    snapToStrikeYaw(player);
                    CarverSasDiag.finish("timeout-snap", 0.0f, distXZ, settleTicks);
                    stopWalk();
                    CarverPerfLog.sasSettle(settleTicks, 0.0, distXZ);
                    trace("settle timeout, snapped and approving");
                    CarverClientState.sendApprove();
                }
            }
            settleTicks++;
            return;
        }
        settling = false;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float curYaw = player.getYRot();
        player.setYRot(curYaw + wrapDegrees(yaw - curYaw) * 0.35f);
        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(distXZ > SPRINT_BEYOND);
        minecraft.options.keyJump.setDown(player.horizontalCollision);
        if ((ticks & 15) == 0) {
            CarverSasDiag.sample(CarverSasDiag.WALK,
                    player.getX(), player.getZ(), player.getYRot(), yaw, distXZ);
        }
    }

    /** Any mouse press hands control back to the artisan immediately. */
    public static void abortOnInput() {
        if (active) abort();
    }

    public static void abort() {
        if (active) trace("aborted");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) stopKeys(minecraft);
        CarverSasDiag.abort(active ? "user" : "idle");
        active = false;
        settling = false;
        settleTicks = 0;
        wasClose = false;
        aimPlan = null;
        target = null;
        ticks = 0;
    }

    /** Shared walk teardown for the approve paths (diag already finished). */
    private static void stopWalk() {
        active = false;
        settling = false;
        wasClose = false;
        aimPlan = null;
        target = null;
        ticks = 0;
        settleTicks = 0;
    }

    /**
     * Snaps the body onto the strike yaw without moving the feet (leash-safe by
     * construction). Runs under the detached design camera, so the one-frame turn
     * is invisible to the artisan but saves the frozen work pose from facing
     * nowhere when the settle budget runs out.
     */
    private static void snapToStrikeYaw(LocalPlayer player) {
        try {
            if (player != null) player.setYRot(settleYaw);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Settle leash guard: a micro-step is allowed only while the artisan stays
     * inside the conservative margin around the walk origin. Pure geometry over
     * live positions; the server leash anchors fresh at approval anyway.
     */
    private static boolean leashSafe(LocalPlayer player) {
        double dx = player.getX() - walkStartX;
        double dy = player.getY() - walkStartY;
        double dz = player.getZ() - walkStartZ;
        return dx * dx + dy * dy + dz * dz
                < SETTLE_LEASH_SAFE * SETTLE_LEASH_SAFE;
    }

    private static void trace(String message) {
        try {
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER-WALK] " + message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void abortWith(Minecraft minecraft, String message) {
        abort();
        if (minecraft.player != null) {
            minecraft.gui.setOverlayMessage(Component.literal(message), false);
        }
    }

    private static void stopKeys(Minecraft minecraft) {
        try {
            minecraft.options.keyUp.setDown(false);
            minecraft.options.keyJump.setDown(false);
            minecraft.options.keySprint.setDown(false);
        } catch (RuntimeException ignored) {
        }
    }
}
