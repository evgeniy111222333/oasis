package ua.rp.chat.client.stonemason;

import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import ua.rp.chat.stonemason.DraftMask;

/**
 * White chalk survey lines on the live block: every marked cell touching the outer
 * shell of the focused volume gets a thin white cuboid gizmo, mirroring the draft in
 * the world in real time.
 */
public final class StonemasonChalkOverlay {
    private static final int MAX_BOXES_PER_FRAME = 256;
    private static final GizmoStyle CHALK = GizmoStyle.stroke(0xFFFFFFFF);

    private StonemasonChalkOverlay() {
    }

    public static void render() {
        if (!StonemasonClientState.designing()) return;
        BlockPos focus = StonemasonClientState.focus();
        if (focus == null) return;
        DraftMask draft = StonemasonClientState.draft();
        if (draft.isEmpty()) return;
        int drawn = 0;
        for (int cell = 0; cell < DraftMask.CELL_COUNT && drawn < MAX_BOXES_PER_FRAME; cell++) {
            if (!draft.get(cell)) continue;
            int cx = DraftMask.x(cell);
            int cy = DraftMask.y(cell);
            int cz = DraftMask.z(cell);
            if (cx != 0 && cx != 15 && cy != 0 && cy != 15 && cz != 0 && cz != 15) continue;
            Gizmos.cuboid(new AABB(
                    focus.getX() + cx / 16.0, focus.getY() + cy / 16.0, focus.getZ() + cz / 16.0,
                    focus.getX() + (cx + 1) / 16.0, focus.getY() + (cy + 1) / 16.0,
                    focus.getZ() + (cz + 1) / 16.0), CHALK);
            drawn++;
        }
    }
}
