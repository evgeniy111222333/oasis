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
        Vec3 stand = pickStand(player.x, player.y, player.z,
                minecraft.player.getYRot(), focus, levelProbe(minecraft));
        if (stand == null) {
            trace("no stand found, approving immediately");
            CarverClientState.sendApprove();
            return;
        }
        target = stand;
        ticks = 0;
        active = true;
        trace("walking to " + stand);
        CarverClientState.sendAutowalk();
    }

    /** Standability probe over absolute block positions. Pure: unit-testable with grids. */
    public interface Solidity {
        boolean solid(int x, int y, int z);

        boolean free(int x, int y, int z);
    }

    /**
     * Stand cell hugging the focus: one of the four side columns with free feet and
     * head plus solid ground within two blocks up or down. Scores closeness to the
     * player, penalizes height gaps and rewards the side already faced, so the walk
     * turns as little as possible. Pure over the probe: unit-testable.
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
        if (ticks++ > TIMEOUT_TICKS) {
            // Walk stalled (blocked, keys ineffective, no path): start the work
            // from here anyway instead of dead-ending the SPACE press. The work
            // leash anchors fresh at approval, so distance stays legal.
            stopKeys(minecraft);
            active = false;
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
            stopKeys(minecraft);
            active = false;
            trace("arrived, approving");
            CarverClientState.sendApprove();
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(distXZ > SPRINT_BEYOND);
        minecraft.options.keyJump.setDown(player.horizontalCollision);
    }

    /** Any mouse press hands control back to the artisan immediately. */
    public static void abortOnInput() {
        if (active) abort();
    }

    public static void abort() {
        if (active) trace("aborted");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) stopKeys(minecraft);
        active = false;
        target = null;
        ticks = 0;
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
