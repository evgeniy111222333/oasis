package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.carver.CarverWorkAim;
import ua.rp.chat.carver.CarverWorkStroke;
import ua.rp.chat.carver.DraftMask;

public final class CarverWorkPoseCache {
    public record Pose(double[] contact, double[] butt, float leftYaw, float leftPitch,
                       float leftElbow, float rightYaw, float rightPitch, float rightElbow,
                       double lift, double contactPulse) {
    }

    /** Render detail by observer distance: full IK near, cheap blend mid, frozen far. */
    public enum Lod { NEAR, MID, FAR }

    /** Squared distances for the observer LOD bands (12 and 32 blocks). */
    public static final double LOD_MID_SQ = 144.0;
    public static final double LOD_FAR_SQ = 1024.0;

    private static final double L1 = 0.375;
    private static final double L2 = 0.375;
    private static final double HANDLE = 0.55;
    private static final double TOOL_LEN = 0.81;

    private CarverWorkPoseCache() {
    }

    /** Pure LOD classifier over squared observer distance. Unit-testable. */
    public static Lod lodFor(double distSq) {
        if (!(distSq >= 0.0)) return Lod.NEAR;
        if (distSq > LOD_FAR_SQ) return Lod.FAR;
        if (distSq > LOD_MID_SQ) return Lod.MID;
        return Lod.NEAR;
    }

    private record CacheEntry(Pose prev, Pose curr, double tick) {
    }

    private static final java.util.Map<java.util.UUID, CacheEntry> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final double NO_TICK = -1.0e18;

    /**
     * Tick-rate pose for the local artisan: at most one full solve per integer work
     * tick, every other frame lerps between the two neighbouring snapshots. The
     * render thread must call this instead of {@link #poseForPlan} directly.
     */
    public static Pose renderPlan(Player player, BlockPos focus,
                                  ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan,
                                  double smoothTicks, int totalTicks, float partial) {
        if (player == null || focus == null) return null;
        double tick = Math.floor(Math.max(0.0, smoothTicks));
        float part = Math.max(0.0f, Math.min(1.0f, partial));
        java.util.UUID id = player.getUUID();
        CacheEntry entry = CACHE.get(id);
        if (entry == null || entry.tick() != tick) {
            long t0 = System.nanoTime();
            Pose solved;
            try {
                solved = poseForPlan(player, focus, plan, tick, totalTicks);
            } catch (RuntimeException failed) {
                return entry == null ? null : entry.curr();
            }
            if (solved == null) return entry == null ? null : entry.curr();
            Pose prev = entry == null ? solved : entry.curr();
            CACHE.put(id, new CacheEntry(prev, solved, tick));
            CarverPerfLog.pose(System.nanoTime() - t0);
            entry = CACHE.get(id);
        }
        if (entry.prev() == entry.curr()) return entry.curr();
        return lerp(entry.prev(), entry.curr(), part);
    }

    /**
     * Tick-rate pose for an observed artisan: the fallback center pose is shifted by
     * the broadcast contact so watchers see the same strike point. MID refreshes
     * every 2nd tick, FAR every 10th; NEAR behaves like the local path.
     */
    public static Pose renderObserved(Player player, BlockPos focus, double[] observedContact,
                                      double smoothTicks, int totalTicks, float partial,
                                      double distSq) {
        if (player == null || focus == null) return null;
        Lod lod = lodFor(distSq);
        double tick = Math.floor(Math.max(0.0, smoothTicks));
        float part = Math.max(0.0f, Math.min(1.0f, partial));
        java.util.UUID id = player.getUUID();
        CacheEntry entry = CACHE.get(id);
        int cadence = lod == Lod.FAR ? 10 : lod == Lod.MID ? 2 : 1;
        boolean due = entry == null || (tick - entry.tick()) >= cadence;
        if (due) {
            long t0 = System.nanoTime();
            Pose solved;
            try {
                solved = poseFor(player, focus, null, tick, totalTicks);
                if (solved != null && observedContact != null) {
                    double[] oldC = solved.contact();
                    double[] oldB = solved.butt();
                    double[] newB = new double[]{observedContact[0] + oldB[0] - oldC[0],
                            observedContact[1] + oldB[1] - oldC[1],
                            observedContact[2] + oldB[2] - oldC[2]};
                    solved = new Pose(observedContact.clone(), newB,
                            solved.leftYaw(), solved.leftPitch(), solved.leftElbow(),
                            solved.rightYaw(), solved.rightPitch(), solved.rightElbow(),
                            solved.lift(), solved.contactPulse());
                }
            } catch (RuntimeException failed) {
                return entry == null ? null : entry.curr();
            }
            if (solved == null) return entry == null ? null : entry.curr();
            Pose prev = entry == null ? solved : entry.curr();
            CACHE.put(id, new CacheEntry(prev, solved, tick));
            CarverPerfLog.pose(System.nanoTime() - t0);
            entry = CACHE.get(id);
        }
        if (lod == Lod.FAR || entry.prev() == entry.curr()) return entry.curr();
        return lerp(entry.prev(), entry.curr(), part);
    }

    /** Drops all cached snapshots (session end, disconnect). */
    public static void clear() {
        CACHE.clear();
    }

    /** Frame blend between two tick snapshots. Pure. */
    public static Pose lerp(Pose a, Pose b, float t) {
        if (a == null) return b;
        if (b == null) return a;
        if (!(t > 0.0f)) return a;
        if (!(t < 1.0f)) return b;
        double[] c = new double[]{lerp1(a.contact()[0], b.contact()[0], t),
                lerp1(a.contact()[1], b.contact()[1], t),
                lerp1(a.contact()[2], b.contact()[2], t)};
        double[] butt = new double[]{lerp1(a.butt()[0], b.butt()[0], t),
                lerp1(a.butt()[1], b.butt()[1], t),
                lerp1(a.butt()[2], b.butt()[2], t)};
        return new Pose(c, butt,
                lerp1(a.leftYaw(), b.leftYaw(), t), lerp1(a.leftPitch(), b.leftPitch(), t),
                lerp1(a.leftElbow(), b.leftElbow(), t), lerp1(a.rightYaw(), b.rightYaw(), t),
                lerp1(a.rightPitch(), b.rightPitch(), t), lerp1(a.rightElbow(), b.rightElbow(), t),
                lerp1(a.lift(), b.lift(), t), lerp1(a.contactPulse(), b.contactPulse(), t));
    }

    private static double lerp1(double a, double b, float t) {
        return a + (b - a) * t;
    }

    private static float lerp1(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static Pose poseFor(Player player, BlockPos focus, DraftMask draft,
                               double smoothTicks, int totalTicks) {
        if (player == null || focus == null) return null;
        ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan = null;
        try {
            java.util.List<Integer> cells = draft == null ? null : draft.cells();
            plan = ua.rp.chat.carver.CarverStrikeAlign.solve(
                    focus.getX(), focus.getY(), focus.getZ(), cells,
                    player.getX(), player.getY(), player.getZ());
        } catch (RuntimeException ignored) {
        }
        return poseForPlan(player, focus, plan, smoothTicks, totalTicks);
    }

    /**
     * Plan-based pose: contact and normal come from the shared StrikePlan so the
     * walk stance, the camera target and the hands all agree on one strike point.
     * The hammer butt travels on the normal-locked trajectory, so the visual impact
     * always lands on the contact regardless of approach side.
     */
    public static Pose poseForPlan(Player player, BlockPos focus,
                                   ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan,
                                   double smoothTicks, int totalTicks) {
        if (player == null || focus == null) return null;
        Vec3 contactW;
        Vec3 normal;
        if (plan == null) {
            double[] centroid = null;
            try {
                centroid = CarverWorkAim.contactWorld(focus, null);
            } catch (RuntimeException ignored) {
            }
            contactW = centroid == null
                    ? new Vec3(focus.getX() + 0.5, focus.getY() + 0.6, focus.getZ() + 0.5)
                    : new Vec3(centroid[0], centroid[1], centroid[2]);
            normal = new Vec3(0.0, 1.0, 0.0);
        } else {
            contactW = new Vec3(plan.contactX(), plan.contactY(), plan.contactZ());
            normal = new Vec3(plan.normalX(), plan.normalY(), plan.normalZ());
        }
        Vec3 eye = player.getEyePosition();
        Vec3 toPlayer = eye.subtract(contactW);
        toPlayer = new Vec3(toPlayer.x, 0.0, toPlayer.z);
        if (toPlayer.lengthSqr() < 1.0e-6) toPlayer = new Vec3(0.0, 0.0, 1.0);
        toPlayer = toPlayer.normalize();
        if (normal.dot(toPlayer) < 0.0) normal = normal.scale(-1.0);
        double cycle = CarverWorkStroke.cycleOf(smoothTicks, Math.max(1, totalTicks));
        double lift = CarverWorkStroke.lift(cycle);
        double pulse = CarverWorkStroke.contact(cycle);
        double[] butt = ua.rp.chat.carver.CarverTrajectory.buttPoint(
                contactW.x, contactW.y, contactW.z,
                normal.x, normal.y, normal.z,
                toPlayer.x, 0.0, toPlayer.z, lift);
        // Impact snap: pin the butt exactly onto the contact at the bottom of the
        // swing so one frame reads as stone contact even under partial-tick error.
        if (pulse > 0.9) {
            double[] impact = ua.rp.chat.carver.CarverTrajectory.impactPoint(
                    contactW.x, contactW.y, contactW.z, normal.x, normal.y, normal.z);
            double snap = (pulse - 0.9) / 0.1;
            for (int i = 0; i < 3; i++) butt[i] += (impact[i] - butt[i]) * snap;
        }
        Vec3 buttW = new Vec3(butt[0], butt[1], butt[2]);
        Vec3 shaft = buttW.subtract(contactW);
        if (shaft.lengthSqr() < 1.0e-9) shaft = normal;
        else shaft = shaft.normalize();
        Vec3 leftHandW = contactW.add(shaft.scale(HANDLE));
        Vec3 rightHandW = buttW.add(toPlayer.scale(0.02));
        float bodyYaw = player.getYRot();
        double[] left = solveArm(player, leftHandW, bodyYaw);
        double[] right = solveArm(player, rightHandW, bodyYaw);
        Pose pose = new Pose(new double[]{contactW.x, contactW.y, contactW.z},
                new double[]{buttW.x, buttW.y, buttW.z},
                (float) left[0], (float) left[1], (float) left[2],
                (float) right[0], (float) right[1], (float) right[2],
                lift, pulse);
        return pose;
    }

    private static double[] solveArm(Player player, Vec3 handW, float bodyYaw) {
        Vec3 shoulderW = shoulderWorld(player, bodyYaw);
        Vec3 d = handW.subtract(shoulderW);
        double yawRad = Math.atan2(-d.x, d.z) - Math.toRadians(bodyYaw);
        while (yawRad > Math.PI) yawRad -= Math.PI * 2.0;
        while (yawRad < -Math.PI) yawRad += Math.PI * 2.0;
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        double dist = Math.max(0.2, Math.min(L1 + L2 - 0.01, d.length()));
        double cosEl = (L1 * L1 + L2 * L2 - dist * dist) / (2.0 * L1 * L2);
        cosEl = Math.max(-1.0, Math.min(1.0, cosEl));
        double elbow = Math.PI - Math.acos(cosEl);
        double pitch = -Math.PI / 2.0 - Math.atan2(d.y, Math.max(0.05, horiz)) * 0.62;
        pitch = Math.max(-2.4, Math.min(-0.4, pitch));
        elbow = Math.max(0.25, Math.min(2.4, elbow));
        return new double[]{yawRad * 0.55, pitch, elbow};
    }

    private static Vec3 shoulderWorld(Player player, float bodyYaw) {
        double rad = Math.toRadians(bodyYaw);
        double sideX = Math.cos(rad) * 0.28;
        double sideZ = Math.sin(rad) * 0.28;
        return new Vec3(player.getX() + sideX, player.getY() + 1.42, player.getZ() + sideZ);
    }
}
