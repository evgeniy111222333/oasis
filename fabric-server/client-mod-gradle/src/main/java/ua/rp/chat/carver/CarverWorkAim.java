package ua.rp.chat.carver;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class CarverWorkAim {
    private CarverWorkAim() {
    }

    public static double[] draftCentroid(DraftMask mask) {
        if (mask == null || mask.isEmpty()) return null;
        List<Integer> cells = mask.cells();
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (int cell : cells) {
            x += DraftMask.x(cell) + 0.5;
            y += DraftMask.y(cell) + 0.5;
            z += DraftMask.z(cell) + 0.5;
        }
        double count = cells.size();
        return new double[]{x / count, y / count, z / count};
    }

    public static double[] contactWorld(BlockPos focus, DraftMask mask) {
        if (focus == null) return null;
        double[] centroid = draftCentroid(mask);
        if (centroid == null) return null;
        return new double[]{
                focus.getX() + centroid[0] / 16.0,
                focus.getY() + centroid[1] / 16.0,
                focus.getZ() + centroid[2] / 16.0};
    }

    public static int faceNormalAxis(DraftMask mask) {
        if (mask == null || mask.isEmpty()) return -1;
        List<Integer> cells = mask.cells();
        double count = cells.size();
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        for (int cell : cells) {
            mx += DraftMask.x(cell);
            my += DraftMask.y(cell);
            mz += DraftMask.z(cell);
        }
        mx /= count;
        my /= count;
        mz /= count;
        double vx = 0.0;
        double vy = 0.0;
        double vz = 0.0;
        for (int cell : cells) {
            double dx = DraftMask.x(cell) - mx;
            double dy = DraftMask.y(cell) - my;
            double dz = DraftMask.z(cell) - mz;
            vx += dx * dx;
            vy += dy * dy;
            vz += dz * dz;
        }
        if (vy <= vx && vy <= vz) return 1;
        if (vx <= vz) return 0;
        return 2;
    }
}
