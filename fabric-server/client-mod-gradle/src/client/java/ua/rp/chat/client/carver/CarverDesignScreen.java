package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ua.rp.chat.carver.CarverBoxSelect;
import ua.rp.chat.carver.CarverCursorPick;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.CarverStrokeLine;
import ua.rp.chat.carver.DraftMask;

import java.util.ArrayList;
import java.util.List;

/**
 * Slim drafting sidebar: the game view stays the hero (orbit, direct painting), the
 * panel keeps only steppers, toggles, saving and clearing. Faces follow the picked
 * surface automatically; undo/redo, mirror, layers, depth, box and tool live on
 * rebindable keys as well. Strokes and boxes flush to the server on release; the
 * server stays authoritative for the draft. Right-drag orbits, the wheel zooms
 * (Shift+wheel steps the layer instead).
 */
public class CarverDesignScreen extends Screen {
    private static final int KEY_SPACE = 32;
    private static final int KEY_ESCAPE = 256;
    private static final int BUTTON_LEFT = 0;
    private static final int BUTTON_RIGHT = 1;
    private static final int PANEL_W = 104;
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 4;

    /** Shift+drag in progress: the pointer marks a whole box instead of a free stroke. */
    private boolean boxDragging;
    /** Box select carves the picked surface slice only: flat areas, no depth dial. */
    private static final int BOX_DEPTH = 1;
    private final DraftMask stroke = new DraftMask();
    private boolean painting;
    private int[] dragStart;
    private int[] dragNow;
    /** Volume cell under the free cursor; the microvoxel radial freezes this as its action target. */
    private ua.rp.chat.microvoxel.MicrovoxelRaycaster.Hit hoveredVolumeHit;
    private final List<UiButton> buttons = new ArrayList<>();
    /** One-shot pick diagnostics: logs the first few cursor resolves of each drafting session. */
    private static int pickDiag;
    /** Pick diagnostics are opt-in: {@code -Drpchat.carver.debug=true}. */
    private static final boolean DEBUG = Boolean.getBoolean("rpchat.carver.debug");
    private UiButton peelButton;
    private UiButton isolateButton;
    private UiButton boxButton;

    protected CarverDesignScreen() {
        super(Component.translatable("screen.eclipse.carver_design"));
    }

    @Override
    protected void init() {
        pickDiag = 0;
        buttons.clear();
    }

    /**
     * The drafting view must read as a normal camera, never as a menu: vanilla blurs
     * the world behind most screens, so the blurred-background pass is disabled and
     * only a faint dim stays for panel contrast.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x18000000);
        // The radial menu (G) owns every tool now: no sidebar panel, just the world.
        // One compact hint line centred just above the hotbar: the drafting view stays clean.
        String hint = Component.translatable("screen.eclipse.carver_hint").getString();
        int hintWidth = font.width(hint);
        int hintX = Math.max(4, (width - hintWidth) / 2);
        int hintY = height - 58;
        graphics.fill(hintX - 6, hintY - 4, hintX + hintWidth + 6, hintY + 13, 0xA812100E);
        graphics.text(font, hint, hintX, hintY, 0xFFB0B0B0);
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "...";
        String trimmed = value;
        while (!trimmed.isEmpty() && font.width(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? ellipsis : trimmed + ellipsis;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Any press hands movement back: painting and walking never overlap.
        CarverAutoWalk.abortOnInput();
        for (UiButton button : buttons) {
            if (button.contains(event.x(), event.y())) {
                button.action.run();
                return true;
            }
        }
        if (event.button() == BUTTON_LEFT) {
            if (CarverClientState.placing() && CarverClientState.placeMaterial().isEmpty()) {
                if (minecraft != null) {
                    minecraft.gui.setOverlayMessage(
                            Component.literal("Сначала выберите «Материал» в меню (G)."), false);
                }
                return true;
            }
            CarverCursorPick.Hit hit = pickAt(event.x(), event.y());
            if (hit != null) {
                painting = true;
                CarverClientState.setViewFace(hit.face());
                if (isShiftDown()) {
                    // Shift+drag marks a whole area (box) in the active draft mode.
                    boxDragging = true;
                    dragStart = new int[]{hit.cell(), hit.face().ordinal()};
                    dragNow = null;
                    updatePendingBox();
                } else {
                    boxDragging = false;
                    paint(hit);
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == BUTTON_RIGHT) {
            CarverCameraRig.orbitDrag(dragX, dragY);
            return true;
        }
        if (painting && event.button() == BUTTON_LEFT) {
            CarverCursorPick.Hit hit = pickAt(event.x(), event.y());
            if (hit == null) return true;
            if (boxDragging) {
                // The drag anchor stores {cell, faceOrdinal}: the face lives in
                // slot 1, slot 0 is the volume cell and never a valid ordinal.
                if (dragStart == null) return true;
                CarverFaceSlicer.Face face = faceFromOrdinal(dragStart[1]);
                int[] to = CarverFaceSlicer.inverse(face, hit.cell());
                dragNow = new int[]{to[0], to[1]};
                updatePendingBox();
            } else {
                // In place mode the stroke follows the empty neighbour of the picked face.
                boolean placing = CarverClientState.placing();
                int target = placing ? placeTarget(hit) : hit.cell();
                CarverClientState.setHoverPlaceCell(placing ? target : -1);
                if (target < 0) {
                    // No vacant cell under the cursor: never overwrite, just skip this sample.
                    return true;
                }
                if (dragStart == null || dragStart[0] != target) {
                    paintLine(dragStart == null ? target : dragStart[0], target);
                }
                dragStart = new int[]{target, 0};
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (painting && event.button() == BUTTON_LEFT) {
            painting = false;
            if (boxDragging) {
                flushBoxDrag();
            } else {
                flushStroke();
            }
            boxDragging = false;
            CarverClientState.clearPendingBox();
            dragStart = null;
            dragNow = null;
            return true;
        }
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // Keep the hovered volume cell fresh and, in place mode, the hinted placement cell for
        // the hologram ghost outline.
        if (minecraft != null && minecraft.screen == this) {
            CarverCursorPick.Hit hit = pickAt(mouseX, mouseY);
            if (CarverClientState.placing() && hit != null) {
                CarverClientState.setHoverPlaceCell(placeTarget(hit));
            } else {
                CarverClientState.setHoverPlaceCell(-1);
            }
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isShiftDown()) {
            CarverClientState.setViewLayer(CarverClientState.viewLayer()
                    + (verticalAmount > 0.0 ? 1 : -1));
        } else {
            CarverCameraRig.zoom(-verticalAmount);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int code = event.key();
        // Global key binds are not processed while a screen is open, so the microvoxel radial
        // key must be honoured here too (the player opens the wheel from inside the Carver UI).
        if (ua.rp.chat.client.microvoxel.MicrovoxelInteractionController.isRadialKey(event)) {
            ua.rp.chat.client.microvoxel.MicrovoxelInteractionController.openRadial();
            return true;
        }
        // The Ctrl modifier is part of the key mapping now, so a plain Z/Y stays free.
        if (CarverKeybinds.undo.matches(event)) {
            CarverClientState.sendUndo();
            return true;
        }
        if (CarverKeybinds.redo.matches(event)) {
            CarverClientState.sendRedo();
            return true;
        }
        if (code == KEY_SPACE) {
            flushLatched();
            // SPACE walks the artisan up tight to the workpiece first; arrival
            // sends the normal approve, so the fall and the storm stay on rails.
            // (Never close/cancel here: approval requires a live session, and the
            // burst belongs to the touchdown impact, not to the keypress.)
            CarverAutoWalk.start();
            return true;
        }
        if (code == KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (CarverKeybinds.mirrorX.matches(event)) {
            CarverClientState.sendMirror(CarverClientState.mirrorAxes() ^ 0x1);
            return true;
        }
        if (CarverKeybinds.mirrorZ.matches(event)) {
            CarverClientState.sendMirror(CarverClientState.mirrorAxes() ^ 0x2);
            return true;
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (!isShiftDown()) {
            flushLatched();
        }
        return true;
    }

    @Override
    public void onClose() {
        // SPACE doubles as the approve key, so vanilla may latch a jump trigger in
        // the same frame the screen closes: kill it here or the artisan hops.
        try {
            if (Minecraft.getInstance() != null && Minecraft.getInstance().options != null) {
                Minecraft.getInstance().options.keyJump.setDown(false);
            }
        } catch (RuntimeException ignored) {
        }
        CarverAutoWalk.abort();
        if (painting) {
            painting = false;
            if (boxDragging) flushBoxDrag();
            else flushStroke();
            boxDragging = false;
        }
        flushLatched();
        CarverClientState.clearPendingBox();
        dragStart = null;
        dragNow = null;
        if (CarverClientState.designing()) {
            CarverClientState.sendCancel();
        }
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private CarverCursorPick.Hit pickAt(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return null;
        hoveredVolumeHit = null;
        BlockPos focus = CarverClientState.focus();
        if (focus == null || !CarverClientState.designing()) return null;
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 pos = camera.position();
        // Use the exact FOV the frame was projected with. The projection is built from
        // Camera.getFov() (base option * fovModifier), so reading the option directly made
        // the pick drift whenever the modifier was not 1 (sprint/fly/fluid): the cursor and
        // the selected voxel diverged further from the screen centre the further you clicked.
        float fov = camera.getFov();
        if (!(fov > 1.0f) || !Float.isFinite(fov)) {
            try {
                fov = (float) minecraft.options.fov().get().intValue();
            } catch (RuntimeException ignored) {
                fov = 70.0f;
            }
        }
        double lift = CarverHologram.visualLift();
        double offX = CarverHologram.offsetX();
        double offZ = CarverHologram.offsetZ();
        // Carved volumes pick through the live voxel raycast at the hologram
        // anchor: cavities see through to inner walls, removed cells never hit.
        // Fresh sockets fall back to the full-cube slab below.
        CarverCursorPick.Hit result = pickVolume(pos, camera, fov, mouseX, mouseY,
                focus, lift, offX, offZ);
        if (result == null && !hasCarvedVolume(focus)) {
            result = CarverCursorPick.pick(pos.x, pos.y, pos.z,
                    camera.yRot(), camera.xRot(), fov, width, height,
                    mouseX, mouseY, focus.getX(), focus.getY() + lift, focus.getZ(),
                    offX, offZ);
        }
        if (DEBUG && pickDiag < 4) {
            pickDiag++;
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER-PICK] mouse=" + mouseX + "," + mouseY
                    + " fov=" + fov + " cam=" + pos
                    + " yaw=" + camera.yRot() + " pitch=" + camera.xRot()
                    + " lift=" + lift + " hit=" + (result == null ? "null"
                            : (result.cell() + "/" + result.face())));
        }
        return result;
    }

    /** Raw volume hit under the free cursor; consumed by the microvoxel radial when it opens. */
    public ua.rp.chat.microvoxel.MicrovoxelRaycaster.Hit hoveredVolumeHit() {
        return hoveredVolumeHit;
    }

    private CarverCursorPick.Hit pickVolume(net.minecraft.world.phys.Vec3 pos,
                                            net.minecraft.client.Camera camera, float fov,
                                            double mouseX, double mouseY, BlockPos focus,
                                            double lift, double offX, double offZ) {
        // Unified source: the synced carving, or the virtual full cube for a fresh socket.
        // The draft is fed in as a hidden mask so already-carved cells read as air and the
        // ray lands on the freshly exposed cavity wall, which is what lets the artisan keep
        // stepping one layer deeper without leaving the session.
        ua.rp.chat.microvoxel.MicrovoxelVolume volume;
        try {
            volume = CarverHologramRenderer.sourceVolume(focus);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (volume == null) return null;
        double[] ray = CarverCursorPick.ray(pos.x, pos.y, pos.z,
                camera.yRot(), camera.xRot(), fov, width, height, mouseX, mouseY);
        if (ray == null) return null;
        // The lifted copy floats on fractional height, but volume entries sit on
        // integer blocks: shift the ray down by the same lift instead (translation
        // preserves faces and cells exactly), casting the socket volume itself.
        double reach = pos.distanceTo(new net.minecraft.world.phys.Vec3(
                focus.getX() + 0.5, focus.getY() + 0.5, focus.getZ() + 0.5)) + 2.0;
        ua.rp.chat.microvoxel.MicrovoxelRaycaster.Entry entry =
                new ua.rp.chat.microvoxel.MicrovoxelRaycaster.Entry(
                        focus.getX(), focus.getY(), focus.getZ(), volume);
        ua.rp.chat.microvoxel.MicrovoxelRaycaster.Hit hit;
        try {
            hit = ua.rp.chat.microvoxel.MicrovoxelRaycaster.cast(
                    ray[0] - offX, ray[1] - lift, ray[2] - offZ,
                    ray[3], ray[4], ray[5], reach, java.util.List.of(entry),
                    (maskedEntry, cell) -> CarverClientState.draft().get(cell));
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (hit == null) return null;
        hoveredVolumeHit = hit;
        CarverFaceSlicer.Face face;
        try {
            face = CarverFaceSlicer.Face.valueOf(hit.face().name());
        } catch (RuntimeException unknown) {
            return null;
        }
        return new CarverCursorPick.Hit(hit.cell(), face, hit.distance());
    }

    private boolean hasCarvedVolume(BlockPos focus) {
        try {
            var cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            return cached != null && cached.volume != null
                    && ua.rp.chat.microvoxel.MicrovoxelVolume.dominantMaterial(
                            cached.volume) != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** 3D line fill between two picks so diagonals paint areas, not dotted paths. */
    private void paintLine(int fromCell, int toCell) {
        for (int cell : CarverStrokeLine.cellsBetween(fromCell, toCell)) {
            paint(cell);
        }
    }

    private void updatePendingBox() {
        if (dragStart == null) {
            CarverClientState.clearPendingBox();
            return;
        }
        CarverFaceSlicer.Face face = faceFromOrdinal(dragStart[1]);
        int[] from = CarverFaceSlicer.inverse(face, dragStart[0]);
        int[] to = dragNow == null ? from : dragNow;
        int layer = CarverClientState.layerOf(face, dragStart[0]);
        int[] bounds = CarverBoxSelect.boundsFor(face,
                from[0], from[1], to[0], to[1], layer, BOX_DEPTH);
        CarverClientState.setPendingBox(bounds[0], bounds[1], bounds[2],
                bounds[3], bounds[4], bounds[5]);
    }

    private void flushBoxDrag() {
        if (dragStart == null) return;
        CarverFaceSlicer.Face face = faceFromOrdinal(dragStart[1]);
        int[] from = CarverFaceSlicer.inverse(face, dragStart[0]);
        int[] to = dragNow == null ? from : dragNow;
        int layer = CarverClientState.layerOf(face, startCellOfDrag());
        int[] cells = CarverBoxSelect.cellsFor(face,
                from[0], from[1], to[0], to[1], layer, BOX_DEPTH);
        boolean placing = CarverClientState.placing();
        DraftMask box = new DraftMask();
        if (placing) {
            // The box builds outward: mark the vacant layer adjacent to the picked face.
            int[] normal = faceNormal(face);
            for (int cell : cells) {
                int x = DraftMask.x(cell) + normal[0];
                int y = DraftMask.y(cell) + normal[1];
                int z = DraftMask.z(cell) + normal[2];
                if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) continue;
                int target = DraftMask.index(x, y, z);
                if (isPlaceVacant(target)) box.set(target);
            }
            if (!box.isEmpty()) CarverClientState.sendPlaceBox(box);
        } else {
            for (int cell : cells) {
                if (!CarverClientState.isPaintable(cell)) continue;
                box.set(cell);
            }
            if (!box.isEmpty()) CarverClientState.sendBox(true, box);
        }
        dragStart = null;
        dragNow = null;
    }

    private int startCellOfDrag() {
        return dragStart == null ? -1 : dragStart[0];
    }

    private final DraftMask latched = new DraftMask();

    /**
     * Live preview of the unflushed input: the current stroke plus latched cells.
     * Read-only snapshot for the overlay; the authoritative draft stays untouched
     * until flush.
     */
    public static DraftMask livePreviewMask() {
        Minecraft live = Minecraft.getInstance();
        if (!(live.screen instanceof CarverDesignScreen screen)) return new DraftMask();
        DraftMask preview = screen.stroke.copy();
        preview.orIn(screen.latched);
        return preview;
    }

    private void paintLatched() {
        if (stroke.isEmpty()) return;
        latched.orIn(stroke);
        stroke.clearAll();
    }

    private void flushLatched() {
        if (latched.isEmpty()) return;
        DraftMask box = latched.copy();
        latched.clearAll();
        CarverClientState.sendBox(true, box);
    }

    private void flushStroke() {
        if (!stroke.isEmpty()) {
            if (CarverClientState.placing()) {
                CarverClientState.sendPlaceStroke(stroke);
            } else {
                CarverClientState.sendStroke(true, stroke);
            }
            stroke.clearAll();
        }
    }

    private void paint(int cell) {
        // The stroke mask always collects candidate cells; the active draft mode at
        // flush time decides whether they are removed or placed.
        if (!CarverClientState.placing() && !CarverClientState.isPaintable(cell)) return;
        stroke.set(cell);
    }

    private void paint(CarverCursorPick.Hit hit) {
        int target = CarverClientState.placing() ? placeTarget(hit) : hit.cell();
        if (target >= 0) paint(target);
    }

    /**
     * The cell a placement would fill: the vacant neighbour of the picked face. Returns -1 when
     * that neighbour is out of the volume or already occupied — a placement must never overwrite
     * an existing voxel, only grow into empty space (or a cell already drafted for removal).
     */
    private int placeTarget(CarverCursorPick.Hit hit) {
        int x = DraftMask.x(hit.cell());
        int y = DraftMask.y(hit.cell());
        int z = DraftMask.z(hit.cell());
        int[] normal = faceNormal(hit.face());
        int nx = x + normal[0];
        int ny = y + normal[1];
        int nz = z + normal[2];
        if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) return -1;
        int target = DraftMask.index(nx, ny, nz);
        return isPlaceVacant(target) ? target : -1;
    }

    /** Vacant in the previewed sculpt: source air, or a cell already drafted for removal. */
    private boolean isPlaceVacant(int cell) {
        if (CarverClientState.draft().get(cell)) return true;
        try {
            ua.rp.chat.microvoxel.MicrovoxelVolume volume =
                    CarverHologramRenderer.sourceVolume(CarverClientState.focus());
            return volume == null || volume.materialAt(
                    DraftMask.x(cell), DraftMask.y(cell), DraftMask.z(cell)) == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int[] faceNormal(CarverFaceSlicer.Face face) {
        return switch (face) {
            case UP -> new int[]{0, 1, 0};
            case DOWN -> new int[]{0, -1, 0};
            case NORTH -> new int[]{0, 0, -1};
            case SOUTH -> new int[]{0, 0, 1};
            case WEST -> new int[]{-1, 0, 0};
            case EAST -> new int[]{1, 0, 0};
        };
    }

    private static int faceOrdinal(CarverFaceSlicer.Face face) {
        return face.ordinal();
    }

    private static CarverFaceSlicer.Face faceFromOrdinal(int ordinal) {
        CarverFaceSlicer.Face[] faces = CarverFaceSlicer.Face.values();
        if (ordinal < 0 || ordinal >= faces.length) return CarverFaceSlicer.Face.UP;
        return faces[ordinal];
    }

    private boolean isCtrlDown() {
        if (minecraft == null) return false;
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                        window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                        window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private boolean isShiftDown() {
        if (minecraft == null) return false;
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                        window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                        window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static final class UiButton {
        final int x;
        final int y;
        final int w;
        final int h;
        Component label;
        Runnable action;

        UiButton(int x, int y, int w, int h, Component label, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.action = action;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        }
    }
}
