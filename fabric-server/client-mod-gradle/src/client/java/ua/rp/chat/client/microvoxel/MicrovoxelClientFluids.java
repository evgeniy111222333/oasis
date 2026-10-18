package ua.rp.chat.client.microvoxel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

/**
 * Read-only fluid queries against the authoritative fluid mirror, extracted from
 * {@link MicrovoxelClientState}. These mirror the server's voxel-exact water/lava refinement so
 * the client's swimming, submersion, fog and lava-damage reads agree with the server, without
 * simulating anything locally. {@link MicrovoxelClientState} keeps thin delegates so the entity
 * and camera mixins are unchanged.
 */
public final class MicrovoxelClientFluids {
    private MicrovoxelClientFluids() {
    }

    /** Raw authoritative levels for rendering and physics refinement, or null when dry. */
    public static MicrovoxelFluidView at(BlockPos position) {
        return MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(position.immutable());
    }

    /**
     * Client mirror of the server voxel-exact water override: true only when vanilla sees
     * water but feet, mid-body and eyes all stand on dry 1/16 cells of marker-fluid volumes
     * with no vanilla water anywhere in the bounding box.
     */
    public static boolean shouldIgnoreWater(Entity entity) {
        if (MicrovoxelClientVolumeStore.CLIENT_FLUIDS.isEmpty()) return false;
        if (!(entity.level() instanceof ClientLevel level)) return false;
        AABB box = entity.getBoundingBox().inflate(0.001);
        if (box.getXsize() > 4.0 || box.getYsize() > 4.0 || box.getZsize() > 4.0) return false;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) return false;
        boolean touchedMarkerFluid = false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockState state = level.getBlockState(new BlockPos(bx, by, bz));
                    if (state.getFluidState().isEmpty()) continue;
                    if (!MicrovoxelBlocks.isMarker(state)) return false;
                    touchedMarkerFluid = true;
                }
            }
        }
        if (!touchedMarkerFluid) return false;
        Vec3 feet = entity.position();
        Vec3 eye = feet.add(0.0, entity.getEyeHeight(), 0.0);
        Vec3 mid = feet.add(0.0, entity.getBbHeight() / 2.0, 0.0);
        return !cellWet(feet) && !cellWet(mid) && !cellWet(eye);
    }

    /**
     * Client mirror of the server voxel-lava height: max lava fraction touching the bounding
     * box, or -1 when vanilla already sees lava or nothing does.
     */
    public static double voxelLavaHeight(Entity entity) {
        if (MicrovoxelClientVolumeStore.CLIENT_FLUIDS.isEmpty()) return -1.0;
        if (!(entity.level() instanceof ClientLevel level)) return -1.0;
        AABB box = entity.getBoundingBox().inflate(0.001);
        if (box.getXsize() > 4.0 || box.getYsize() > 4.0 || box.getZsize() > 4.0) return -1.0;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) return -1.0;
        double height = -1.0;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    if (!level.getFluidState(pos).isEmpty()
                            && !level.getFluidState(pos).is(Fluids.WATER)) {
                        return -1.0;
                    }
                    if (!MicrovoxelBlocks.isMarker(level.getBlockState(pos))) continue;
                    MicrovoxelFluidView view = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(pos);
                    if (view == null || !view.lava()) continue;
                    int x0 = Math.max(0, (int) Math.floor((box.minX - bx) * 16.0));
                    int x1 = Math.min(15, (int) Math.floor((box.maxX - bx) * 16.0 - 1.0E-7));
                    int y0 = Math.max(0, (int) Math.floor((box.minY - by) * 16.0));
                    int y1 = Math.min(15, (int) Math.floor((box.maxY - by) * 16.0 - 1.0E-7));
                    int z0 = Math.max(0, (int) Math.floor((box.minZ - bz) * 16.0));
                    int z1 = Math.min(15, (int) Math.floor((box.maxZ - bz) * 16.0 - 1.0E-7));
                    for (int cx = x0; cx <= x1; cx++) {
                        for (int cy = y0; cy <= y1; cy++) {
                            for (int cz = z0; cz <= z1; cz++) {
                                int wet = view.level(MicrovoxelVolume.index(cx, cy, cz));
                                if (wet <= 0) continue;
                                double cell = wet / 16.0;
                                if (cell > height) height = cell;
                            }
                        }
                    }
                }
            }
        }
        return height;
    }

    /** Lava height at one exact position, or -1 when dry. Single-cell eye-check path. */
    public static double voxelLavaHeightAt(Entity entity, Vec3 exact) {
        if (MicrovoxelClientVolumeStore.CLIENT_FLUIDS.isEmpty()) return -1.0;
        if (!(entity.level() instanceof ClientLevel)) return -1.0;
        return voxelLavaHeightAt(entity.level(), exact);
    }

    /** Level-based core shared by entity and camera refinements. */
    public static double voxelLavaHeightAt(Level level, Vec3 exact) {
        if (MicrovoxelClientVolumeStore.CLIENT_FLUIDS.isEmpty() || level == null) return -1.0;
        BlockPos pos = BlockPos.containing(exact);
        if (!MicrovoxelBlocks.isMarker(level.getBlockState(pos))) return -1.0;
        MicrovoxelFluidView view = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(pos);
        if (view == null || !view.lava()) return -1.0;
        int cellX = (int) Math.floor((exact.x - pos.getX()) * 16.0);
        int cellY = (int) Math.floor((exact.y - pos.getY()) * 16.0);
        int cellZ = (int) Math.floor((exact.z - pos.getZ()) * 16.0);
        if (cellX < 0 || cellX > 15 || cellY < 0 || cellY > 15 || cellZ < 0 || cellZ > 15) {
            return -1.0;
        }
        int wet = view.level(MicrovoxelVolume.index(cellX, cellY, cellZ));
        return wet > 0 ? wet / 16.0 : -1.0;
    }

    /** True when the exact 1/16 cell under a precise position holds water. */
    public static boolean cellWet(Vec3 exact) {
        BlockPos pos = BlockPos.containing(exact);
        MicrovoxelFluidView view = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(pos);
        if (view == null) return false;
        int cellX = (int) Math.floor((exact.x - pos.getX()) * 16.0);
        int cellY = (int) Math.floor((exact.y - pos.getY()) * 16.0);
        int cellZ = (int) Math.floor((exact.z - pos.getZ()) * 16.0);
        if (cellX < 0 || cellX > 15 || cellY < 0 || cellY > 15 || cellZ < 0 || cellZ > 15) {
            return false;
        }
        return view.level(MicrovoxelVolume.index(cellX, cellY, cellZ)) > 0;
    }

    /**
     * Fluid level at global micro coordinates, transparent across volume borders (floor
     * division, like the solid sampler). Returns 0 outside known fluid data.
     */
    public static int fluidLevelAt(BlockPos base, int x, int y, int z) {
        int offsetX = Math.floorDiv(x, 16);
        int offsetY = Math.floorDiv(y, 16);
        int offsetZ = Math.floorDiv(z, 16);
        BlockPos target = offsetX == 0 && offsetY == 0 && offsetZ == 0 ? base
                : new BlockPos(base.getX() + offsetX, base.getY() + offsetY, base.getZ() + offsetZ);
        MicrovoxelFluidView view = MicrovoxelClientVolumeStore.CLIENT_FLUIDS.get(target);
        if (view == null) return 0;
        return view.level(MicrovoxelVolume.index(
                Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16)));
    }
}
