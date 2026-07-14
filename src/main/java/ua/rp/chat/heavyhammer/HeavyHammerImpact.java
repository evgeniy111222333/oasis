package ua.rp.chat.heavyhammer;

import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.List;

/** Чистая геометрия вмятины тяжёлого молота, не зависящая от Bukkit. */
public final class HeavyHammerImpact {
    private HeavyHammerImpact() {
    }

    public static List<Integer> cells(int anchor, Face face) {
        int ax = MicrovoxelVolume.x(anchor);
        int ay = MicrovoxelVolume.y(anchor);
        int az = MicrovoxelVolume.z(anchor);
        List<Integer> result = new ArrayList<>();
        for (int depth = 0; depth <= 3; depth++) {
            int radius = depth == 0 ? 3 : depth == 1 ? 3 : 2;
            for (int first = -radius; first <= radius; first++) {
                for (int second = -radius; second <= radius; second++) {
                    if (first * first + second * second > radius * radius) continue;
                    int x = ax - face.dx * depth;
                    int y = ay - face.dy * depth;
                    int z = az - face.dz * depth;
                    if (face.dx != 0) {
                        y += first;
                        z += second;
                    } else if (face.dy != 0) {
                        x += first;
                        z += second;
                    } else {
                        x += first;
                        y += second;
                    }
                    if (MicrovoxelVolume.inside(x, y, z)) {
                        result.add(MicrovoxelVolume.index(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public enum Face {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        private final int dx;
        private final int dy;
        private final int dz;

        Face(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }
}
