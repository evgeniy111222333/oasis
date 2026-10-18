package ua.rp.chat.client.microvoxel;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.client.mixin.CubeVoxelShapeInvoker;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.List;

/**
 * Client-side voxel collision-shape construction, extracted from {@link MicrovoxelClientState}.
 *
 * <p>Compiles one {@link MicrovoxelVolume} into a Minecraft {@link VoxelShape}: a merged-cuboid
 * or discrete-grid base for the full cells plus the 1/256-precision sub-cell boxes of any shaped
 * cells. Pure with respect to client state — it only reads the passed volume — so it is safe to
 * run on the async shape-generation worker.</p>
 */
public final class MicrovoxelClientShapes {
    private MicrovoxelClientShapes() {
    }

    public static VoxelShape build(MicrovoxelVolume volume) {
        MicrovoxelVolume.CollisionPlan plan = volume.collisionPlan();
        VoxelShape base;
        if (plan.backend() == MicrovoxelVolume.CollisionBackend.GRID) {
            BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(
                    MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION);
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                // Shaped cells are collided by shapeBoxes below, never as a full 1/16 cube.
                if (volume.occupied(cell) && volume.shapeAt(cell) == 0) {
                    discrete.fill(MicrovoxelVolume.x(cell), MicrovoxelVolume.y(cell), MicrovoxelVolume.z(cell));
                }
            }
            base = CubeVoxelShapeInvoker.eclipse$create(discrete);
        } else {
            List<MicrovoxelVolume.Cuboid> cuboids = plan.cuboids();
            if (cuboids.isEmpty()) {
                base = Shapes.empty();
            } else {
                VoxelShape[] parts = new VoxelShape[cuboids.size()];
                for (int index = 0; index < cuboids.size(); index++) {
                    MicrovoxelVolume.Cuboid cuboid = cuboids.get(index);
                    parts[index] = Shapes.box(
                            cuboid.minX() / 16.0, cuboid.minY() / 16.0, cuboid.minZ() / 16.0,
                            cuboid.maxX() / 16.0, cuboid.maxY() / 16.0, cuboid.maxZ() / 16.0);
                }
                base = combine(parts, 0, parts.length).optimize();
            }
        }

        List<MicrovoxelVolume.SubBox> shaped = volume.shapeBoxes();
        if (shaped.isEmpty()) return base;
        VoxelShape[] parts = new VoxelShape[shaped.size()];
        for (int index = 0; index < shaped.size(); index++) {
            MicrovoxelVolume.SubBox box = shaped.get(index);
            double scale = 1.0 / MicrovoxelVolume.SUB_BLOCK_UNITS;
            parts[index] = Shapes.box(
                    box.minX() * scale, box.minY() * scale, box.minZ() * scale,
                    box.maxX() * scale, box.maxY() * scale, box.maxZ() * scale);
        }
        return Shapes.or(base, combine(parts, 0, parts.length)).optimize();
    }

    /** Balanced binary merge of the per-part shapes, avoiding the deep recursion of a left fold. */
    private static VoxelShape combine(VoxelShape[] shapes, int start, int end) {
        if (start >= end) return Shapes.empty();
        if (start == end - 1) return shapes[start];
        int mid = (start + end) / 2;
        return Shapes.or(combine(shapes, start, mid), combine(shapes, mid, end));
    }
}
