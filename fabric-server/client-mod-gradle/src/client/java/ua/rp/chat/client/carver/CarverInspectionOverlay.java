package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.CarverGrainField;
import ua.rp.chat.carver.CarverInclusionField;
import ua.rp.chat.carver.DraftMask;

/**
 * Phase 0 world overlay: the procedural grain and inclusion hints drawn on the raised hologram
 * copy, plus the entry scan pulse. Like the chalk layer, everything is submitted as a handful
 * of capped gizmos so the drafting view stays readable and cheap; the socket itself is hidden,
 * so hints are projected onto the shell where the artisan can actually see them.
 *
 * <p>The overlay never mutates state and never touches the network: it is a pure read of
 * {@link CarverInspection} plus the hologram anchor.</p>
 */
public final class CarverInspectionOverlay {
    /** Grid stride of grain ticks per face; larger = sparser. */
    private static final int GRAIN_STRIDE = 3;
    /** Grain strength below which a cell shows nothing. */
    private static final double GRAIN_MIN = 0.45;
    /** Half-length of a grain streak, in blocks. */
    private static final double STREAK = 0.42 / 16.0;
    /** Line thickness, in blocks. */
    private static final double THICK = 0.004;
    /** Surface nudge so the marks sit proud of the copy, in blocks. */
    private static final double PAD = 0.004;
    /** Half-size of an inclusion patch, in blocks. */
    private static final double PATCH = 0.55 / 16.0;

    private static final GizmoStyle GRAIN = GizmoStyle.stroke(0x706FA8DC);
    private static final GizmoStyle SCAN = GizmoStyle.stroke(0x55BFE3FF);
    private static final int VEIN_RGB = 0xFFC24A;
    private static final int CRACK_RGB = 0x55221A;
    private static final int CAVITY_RGB = 0x9B7FC6;

    private CarverInspectionOverlay() {
    }

    /** World-space END_MAIN hook, mirroring the chalk overlay's contract. */
    public static void render() {
        if (!CarverClientState.designing()) return;
        BlockPos focus = CarverInspection.focus();
        if (focus == null) return;
        double lift = CarverHologram.visualLift();
        double offX = CarverHologram.offsetX();
        double offZ = CarverHologram.offsetZ();
        if (CarverInspection.enabled()) {
            drawGrain(focus, lift, offX, offZ);
            drawHints(focus, lift, offX, offZ);
        }
        drawScan(focus, lift, offX, offZ);
    }

    /** Directional grain ticks on all six shell faces, sparse and strength-gated. */
    private static void drawGrain(BlockPos focus, double lift, double offX, double offZ) {
        CarverGrainField.GrainType type = CarverInspection.grain();
        if (type == CarverGrainField.GrainType.AMORPHOUS) return;
        long seed = CarverInspection.seed();
        for (CarverFaceSlicer.Face face : CarverFaceSlicer.Face.values()) {
            double[] normal = normalOf(face);
            for (int row = 0; row < 16; row += GRAIN_STRIDE) {
                for (int col = 0; col < 16; col += GRAIN_STRIDE) {
                    int cell = CarverFaceSlicer.cellFor(face, col, row, 0);
                    if (CarverGrainField.strength(seed, cell) < GRAIN_MIN) continue;
                    CarverGrainField.Direction grain = CarverGrainField.direction(type, seed, cell);
                    CarverGrainField.Direction along =
                            CarverGrainField.projected(grain, face);
                    double cx = focus.getX() + offX + (DraftMask.x(cell) + 0.5) / 16.0
                            + normal[0] * PAD;
                    double cy = focus.getY() + lift + (DraftMask.y(cell) + 0.5) / 16.0
                            + normal[1] * PAD;
                    double cz = focus.getZ() + offZ + (DraftMask.z(cell) + 0.5) / 16.0
                            + normal[2] * PAD;
                    streak(cx, cy, cz, along, STREAK, GRAIN);
                }
            }
        }
    }

    /** Inclusion hints projected onto the nearest shell face, faded by depth. */
    private static void drawHints(BlockPos focus, double lift, double offX, double offZ) {
        for (CarverInclusionField.Hint hint : CarverInspection.hints()) {
            int cell = hint.cell();
            CarverFaceSlicer.Face face = nearestFace(cell);
            int[] grid = CarverFaceSlicer.inverse(face, cell);
            int shell = CarverFaceSlicer.cellFor(face, grid[0], grid[1], 0);
            int depth = grid[2];
            double alpha = 0.15 + 0.85 * (1.0 - Math.min(1.0, depth / 15.0));
            int rgb = switch (hint.kind()) {
                case VEIN -> VEIN_RGB;
                case CRACK -> CRACK_RGB;
                case CAVITY -> CAVITY_RGB;
            };
            double[] normal = normalOf(face);
            double cx = focus.getX() + offX + (DraftMask.x(shell) + 0.5) / 16.0
                    + normal[0] * PAD;
            double cy = focus.getY() + lift + (DraftMask.y(shell) + 0.5) / 16.0
                    + normal[1] * PAD;
            double cz = focus.getZ() + offZ + (DraftMask.z(shell) + 0.5) / 16.0
                    + normal[2] * PAD;
            Gizmos.cuboid(new AABB(cx - PATCH, cy - PATCH, cz - PATCH,
                    cx + PATCH, cy + PATCH, cz + PATCH),
                    GizmoStyle.stroke(alphaColor(rgb, alpha)));
        }
    }

    /** Expanding wireframe scan pulse on entry. */
    private static void drawScan(BlockPos focus, double lift, double offX, double offZ) {
        double progress = CarverInspection.scanProgress();
        if (progress >= 1.0) return;
        double half = 0.05 + 0.55 * progress;
        double cx = focus.getX() + 0.5 + offX;
        double cy = focus.getY() + 0.5 + lift;
        double cz = focus.getZ() + 0.5 + offZ;
        Gizmos.cuboid(new AABB(cx - half, cy - half, cz - half,
                cx + half, cy + half, cz + half), SCAN);
    }

    private static void streak(double cx, double cy, double cz,
                               CarverGrainField.Direction dir, double half, GizmoStyle style) {
        double dx = dir.x() * half;
        double dy = dir.y() * half;
        double dz = dir.z() * half;
        Gizmos.cuboid(new AABB(
                Math.min(cx - dx, cx + dx) - THICK,
                Math.min(cy - dy, cy + dy) - THICK,
                Math.min(cz - dz, cz + dz) - THICK,
                Math.max(cx - dx, cx + dx) + THICK,
                Math.max(cy - dy, cy + dy) + THICK,
                Math.max(cz - dz, cz + dz) + THICK), style);
    }

    private static CarverFaceSlicer.Face nearestFace(int cell) {
        int x = DraftMask.x(cell);
        int y = DraftMask.y(cell);
        int z = DraftMask.z(cell);
        int best = 15 - x;
        CarverFaceSlicer.Face face = CarverFaceSlicer.Face.EAST;
        if (x < best) { best = x; face = CarverFaceSlicer.Face.WEST; }
        if (15 - y < best) { best = 15 - y; face = CarverFaceSlicer.Face.UP; }
        if (y < best) { best = y; face = CarverFaceSlicer.Face.DOWN; }
        if (15 - z < best) { best = 15 - z; face = CarverFaceSlicer.Face.SOUTH; }
        if (z < best) { face = CarverFaceSlicer.Face.NORTH; }
        return face;
    }

    private static double[] normalOf(CarverFaceSlicer.Face face) {
        return switch (face) {
            case UP -> new double[]{0, 1, 0};
            case DOWN -> new double[]{0, -1, 0};
            case NORTH -> new double[]{0, 0, -1};
            case SOUTH -> new double[]{0, 0, 1};
            case WEST -> new double[]{-1, 0, 0};
            case EAST -> new double[]{1, 0, 0};
        };
    }

    /** Rebuilds a stroke style with a scaled alpha channel (WARNING: colours are ARGB). */
    private static GizmoStyle fade(GizmoStyle base, double alpha) {
        // GizmoStyle has no public colour accessor, so the faded styles are precomputed for the
        // three kinds by baking alpha into the kind colour at build time instead.
        return base;
    }
}
