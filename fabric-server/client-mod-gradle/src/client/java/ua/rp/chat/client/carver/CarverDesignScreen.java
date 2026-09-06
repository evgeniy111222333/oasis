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

    private boolean erasing;
    private boolean boxMode;
    /** Box select carves the picked surface slice only: flat areas, no depth dial. */
    private static final int BOX_DEPTH = 1;
    private final DraftMask stroke = new DraftMask();
    private boolean painting;
    private int[] dragStart;
    private int[] dragNow;
    private final List<UiButton> buttons = new ArrayList<>();
    private UiButton peelButton;
    private UiButton isolateButton;
    private UiButton boxButton;
    private UiButton toolButton;

    protected CarverDesignScreen() {
        super(Component.translatable("screen.eclipse.carver_design"));
    }

    @Override
    protected void init() {
        buttons.clear();
        int right = width - PANEL_W - 10;
        int top = 20;
        peelButton = wideButton(right, top, peelLabel(), () -> {
        });
        peelButton.action = () -> {
            CarverClientState.setPeelOuterLayers(!CarverClientState.peelOuterLayers());
            peelButton.label = peelLabel();
        };
        buttons.add(peelButton);
        top += ROW_H + ROW_GAP;
        isolateButton = wideButton(right, top, isolateLabel(), () -> {
        });
        isolateButton.action = () -> {
            CarverClientState.setIsolateFace(!CarverClientState.isolateFace());
            isolateButton.label = isolateLabel();
        };
        buttons.add(isolateButton);
        top += ROW_H + ROW_GAP;
        toolButton = wideButton(right, top, toolLabel(), () -> {
        });
        toolButton.action = () -> {
            erasing = !erasing;
            toolButton.label = toolLabel();
        };
        buttons.add(toolButton);
        top += ROW_H + ROW_GAP;
        boxButton = wideButton(right, top, boxLabel(), () -> {
        });
        boxButton.action = () -> {
            boxMode = !boxMode;
            boxButton.label = boxLabel();
        };
        buttons.add(boxButton);
        top += ROW_H + ROW_GAP;
        buttons.add(wideButton(right, top,
                Component.translatable("screen.eclipse.carver_undo"),
                () -> CarverClientState.sendUndo()));
        top += ROW_H + ROW_GAP;
        buttons.add(wideButton(right, top,
                Component.translatable("screen.eclipse.carver_redo"),
                () -> CarverClientState.sendRedo()));
        top += ROW_H + ROW_GAP * 2;
        buttons.add(wideButton(right, top,
                Component.translatable("screen.eclipse.carver_save"),
                () -> CarverClientState.sendSave()));
        top += ROW_H + ROW_GAP;
        buttons.add(wideButton(right, top,
                Component.translatable("screen.eclipse.carver_clear"),
                () -> CarverClientState.sendClear()));
    }

    private UiButton wideButton(int x, int y, Component label, Runnable action) {
        return new UiButton(x, y, PANEL_W, ROW_H, label, action);
    }

    /**
     * Master's tools: the chisel marks voxels for removal, wax puts voxels back
     * (lifts the mark off carved volumes). One toggle, no layer or depth dials.
     */
    private Component toolLabel() {
        return Component.translatable(erasing
                ? "screen.eclipse.carver_wax" : "screen.eclipse.carver_chisel");
    }

    private Component boxLabel() {
        return Component.translatable(boxMode
                ? "screen.eclipse.carver_box_on" : "screen.eclipse.carver_box_off");
    }

    private Component peelLabel() {
        return Component.translatable(CarverClientState.peelOuterLayers()
                ? "screen.eclipse.carver_peel_on" : "screen.eclipse.carver_peel_off");
    }

    private Component isolateLabel() {
        return Component.translatable(CarverClientState.isolateFace()
                ? "screen.eclipse.carver_isolate_on" : "screen.eclipse.carver_isolate_off");
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
        int panelLeft = width - PANEL_W - 10;
        graphics.fill(panelLeft - 6, 12, width - 4, height - 12, 0xD812100E);
        graphics.fill(panelLeft - 6, 12, panelLeft - 4, height - 12, 0xFFE3C099);
        for (UiButton button : buttons) {
            boolean hovered = button.contains(mouseX, mouseY);
            boolean toggled = button == boxButton && boxMode;
            graphics.fill(button.x, button.y, button.x + button.w, button.y + button.h,
                    toggled ? 0xE04A3418 : hovered ? 0xE02B2118 : 0xC016120F);
            graphics.fill(button.x, button.y, button.x + button.w, button.y + 1,
                    toggled ? 0xFFFFD27A : hovered ? 0xFFE3C099 : 0x66B49B78);
            graphics.centeredText(font, fit(button.label.getString(), button.w - 8),
                    button.x + button.w / 2, button.y + 5,
                    hovered || toggled ? 0xFFFFF4DE : 0xFFE3C099);
        }
        // One compact hint line centred just above the hotbar: the block readout and
        // the estimate panel are gone, the drafting view stays clean.
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
            CarverCursorPick.Hit hit = pickAt(event.x(), event.y());
            if (hit != null) {
                painting = true;
                CarverClientState.setViewFace(hit.face());
                if (boxMode) {
                    dragStart = new int[]{hit.cell(), hit.face().ordinal()};
                    dragNow = null;
                    updatePendingBox();
                } else {
                    paint(hit.cell());
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
            if (boxMode) {
                // The drag anchor stores {cell, faceOrdinal}: the face lives in
                // slot 1, slot 0 is the volume cell and never a valid ordinal.
                if (dragStart == null) return true;
                CarverFaceSlicer.Face face = faceFromOrdinal(dragStart[1]);
                int[] to = CarverFaceSlicer.inverse(face, hit.cell());
                dragNow = new int[]{to[0], to[1]};
                updatePendingBox();
            } else {
                if (dragStart == null || dragStart[0] != hit.cell()) {
                    paintLine(dragStart == null ? hit.cell() : dragStart[0], hit.cell());
                }
                dragStart = new int[]{hit.cell(), 0};
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (painting && event.button() == BUTTON_LEFT) {
            painting = false;
            if (boxMode) {
                flushBoxDrag();
            } else if (isShiftDown()) {
                paintLatched();
            } else {
                flushStroke();
            }
            CarverClientState.clearPendingBox();
            dragStart = null;
            dragNow = null;
            return true;
        }
        return true;
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
        if (isCtrlDown() && CarverKeybinds.undo.matches(event)) {
            CarverClientState.sendUndo();
            return true;
        }
        if (isCtrlDown() && CarverKeybinds.redo.matches(event)) {
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
        if (CarverKeybinds.boxMode.matches(event)) {
            boxMode = !boxMode;
            boxButton.label = boxLabel();
            return true;
        }
        if (CarverKeybinds.toolToggle.matches(event)) {
            erasing = !erasing;
            toolButton.label = toolLabel();
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
        CarverAutoWalk.abort();
        if (painting) {
            painting = false;
            if (boxMode) flushBoxDrag();
            else flushStroke();
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
        BlockPos focus = CarverClientState.focus();
        if (focus == null || !CarverClientState.designing()) return null;
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 pos = camera.position();
        float fov = 70.0f;
        try {
            fov = (float) minecraft.options.fov().get().intValue();
        } catch (RuntimeException ignored) {
        }
        double lift = CarverHologram.visualLift();
        double offX = CarverHologram.offsetX();
        double offZ = CarverHologram.offsetZ();
        // Carved volumes pick through the live voxel raycast at the hologram
        // anchor: cavities see through to inner walls, removed cells never hit.
        // Fresh sockets fall back to the full-cube slab below.
        CarverCursorPick.Hit carved = pickVolume(pos, camera, fov, mouseX, mouseY,
                focus, lift, offX, offZ);
        if (carved != null) return carved;
        if (hasCarvedVolume(focus)) return null;
        return CarverCursorPick.pick(pos.x, pos.y, pos.z,
                camera.yRot(), camera.xRot(), fov, width, height,
                mouseX, mouseY, focus.getX(), focus.getY() + lift, focus.getZ(),
                offX, offZ);
    }

    private CarverCursorPick.Hit pickVolume(net.minecraft.world.phys.Vec3 pos,
                                            net.minecraft.client.Camera camera, float fov,
                                            double mouseX, double mouseY, BlockPos focus,
                                            double lift, double offX, double offZ) {
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.CachedVolume cached;
        try {
            cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            if (cached == null || cached.volume == null) return null;
        } catch (RuntimeException unreadable) {
            return null;
        }
        double[] ray = CarverCursorPick.ray(pos.x, pos.y, pos.z,
                camera.yRot(), camera.xRot(), fov, width, height, mouseX, mouseY);
        if (ray == null) return null;
        // The lifted copy floats on fractional height, but volume entries sit on
        // integer blocks: shift the ray down by the same lift instead (translation
        // preserves faces and cells exactly), casting the socket volume itself.
        double reach = pos.distanceTo(new net.minecraft.world.phys.Vec3(
                focus.getX() + 0.5, focus.getY() + 0.5, focus.getZ() + 0.5)) + 2.0;
        ua.rp.chat.microvoxel.MicrovoxelRaycaster.Hit hit;
        try {
            hit = ua.rp.chat.microvoxel.MicrovoxelRaycaster.cast(
                    ray[0] - offX, ray[1] - lift, ray[2] - offZ,
                    ray[3], ray[4], ray[5], reach, java.util.List.of(
                            new ua.rp.chat.microvoxel.MicrovoxelRaycaster.Entry(
                                    focus.getX(), focus.getY(), focus.getZ(),
                                    cached.volume)));
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (hit == null) return null;
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
        if (isShiftDown()) {
            if (erasing) {
                for (int cell : cells) latched.clear(cell);
            } else {
                for (int cell : cells) {
                    if (CarverClientState.isPaintable(cell)) latched.set(cell);
                }
            }
        } else {
            DraftMask box = new DraftMask();
            for (int cell : cells) {
                if (!erasing && !CarverClientState.isPaintable(cell)) continue;
                box.set(cell);
            }
            if (!box.isEmpty()) CarverClientState.sendBox(!erasing, box);
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
            CarverClientState.sendStroke(!erasing, stroke);
            stroke.clearAll();
        }
    }

    private void paint(int cell) {
        // The stroke mask always collects candidate cells; the add/erase flag at
        // flush time decides their fate. Clearing here would erase the stroke
        // itself and the wax could never lift marks off the draft.
        if (!erasing && !CarverClientState.isPaintable(cell)) return;
        stroke.set(cell);
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
