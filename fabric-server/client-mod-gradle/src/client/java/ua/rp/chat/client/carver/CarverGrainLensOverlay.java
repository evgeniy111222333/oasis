package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import ua.rp.chat.carver.CarverGrainField;
import ua.rp.chat.carver.CarverGrainPalette;
import ua.rp.chat.carver.DraftMask;

/**
 * The grain lens: an X-ray schematic of the workpiece that shows the raw domain map instead of
 * the material texture. Each occupied cell is a flat, saturated block of its domain colour and
 * every seam is outlined, so the artisan can literally read the beds, rings, crystal facets and
 * weave before committing a single cut. Toggled with the lens key; purely a read of
 * {@link CarverInspection} plus the live occupancy, nothing here touches the network.
 */
public final class CarverGrainLensOverlay {
    /** Cell stride of the lens; 2 keeps a full 16^3 map under ~512 gizmos. */
    private static final int STRIDE = 2;
    /** Fill alpha of an interior domain cell. */
    private static final int FILL_ALPHA = 0x52;
    /** Stroke colour of a seam. */
    private static final int SEAM_RGB = 0x101014;
    private static final float STROKE = 1.6f;

    private CarverGrainLensOverlay() {
    }

    /** World-space END_MAIN hook, mirroring the inspection overlay's contract. */
    public static void render() {
        if (!CarverClientState.designing() || !CarverInspection.lensActive()) return;
        BlockPos focus = CarverInspection.focus();
        if (focus == null) return;
        CarverGrainField.Field grain = CarverInspection.field();
        if (grain == null || !grain.hasGrain()) return;
        ua.rp.chat.microvoxel.MicrovoxelVolume volume;
        try {
            volume = CarverHologramRenderer.sourceVolume(focus);
        } catch (RuntimeException unreadable) {
            return;
        }
        if (volume == null) return;
        DraftMask draft = CarverClientState.draft();
        double lift = CarverHologram.visualLift();
        double offX = CarverHologram.offsetX();
        double offZ = CarverHologram.offsetZ();
        double ox = focus.getX() + offX;
        double oy = focus.getY() + lift;
        double oz = focus.getZ() + offZ;
        for (int y = 0; y < 16; y += STRIDE) {
            for (int z = 0; z < 16; z += STRIDE) {
                for (int x = 0; x < 16; x += STRIDE) {
                    int cell = x | (z << 4) | (y << 8);
                    if (!volume.occupied(cell) || draft.get(cell)) continue;
                    double x0 = ox + x / 16.0;
                    double y0 = oy + y / 16.0;
                    double z0 = oz + z / 16.0;
                    double x1 = x0 + STRIDE / 16.0;
                    double y1 = y0 + STRIDE / 16.0;
                    double z1 = z0 + STRIDE / 16.0;
                    AABB box = new AABB(x0, y0, z0, x1, y1, z1);
                    int rgb = CarverGrainPalette.lensColor(
                            grain.type(), grain.domain(cell), grain.strength(cell));
                    Gizmos.cuboid(box, GizmoStyle.fill(alpha(rgb, FILL_ALPHA)))
                            .setAlwaysOnTop();
                    if (grain.boundaryness(cell) >= 0.5) {
                        Gizmos.cuboid(box, GizmoStyle.stroke(
                                        alpha(SEAM_RGB, 0xFF), STROKE))
                                .setAlwaysOnTop();
                    }
                }
            }
        }
    }

    private static int alpha(int rgb, int a) {
        return ((a & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }
}
