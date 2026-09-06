package ua.rp.chat.client.stonemason;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import ua.rp.chat.stonemason.DraftMask;
import ua.rp.chat.stonemason.DraftTemplates;

import java.util.ArrayList;
import java.util.List;

/**
 * Drafting table overlay: the live 16x16 slice of the focused block, template bench,
 * pencil/eraser and the master's estimate. Painting collects a stroke mask that is
 * pushed to the server on release; the server stays authoritative for the draft.
 */
public class StonemasonDesignScreen extends Screen {
    private static final int CELL_PX = 14;
    private static final int GRID_PX = CELL_PX * 16;
    private static final int KEY_SPACE = 32;
    private static final int KEY_ESCAPE = 256;
    private static final int BUTTON_LEFT = 0;

    private int layer = 15;
    private boolean erasing;
    private final DraftMask stroke = new DraftMask();
    private boolean painting;
    private int gridLeft;
    private int gridTop;
    private final List<UiButton> buttons = new ArrayList<>();

    protected StonemasonDesignScreen() {
        super(Component.translatable("screen.eclipse.stonemason_design"));
    }

    @Override
    protected void init() {
        gridLeft = (width - GRID_PX) / 2 - 70;
        gridTop = (height - GRID_PX) / 2 - 10;
        buttons.clear();
        int right = gridLeft + GRID_PX + 16;
        int top = gridTop;
        buttons.add(new UiButton(right, top, 56, 20,
                Component.translatable("screen.eclipse.stonemason_layer_down"),
                () -> layer = Math.max(0, layer - 1)));
        buttons.add(new UiButton(right + 60, top, 56, 20,
                Component.translatable("screen.eclipse.stonemason_layer_up"),
                () -> layer = Math.min(15, layer + 1)));
        top += 26;
        buttons.add(new UiButton(right, top, 116, 20,
                Component.translatable("template.eclipse.bath"),
                () -> StonemasonClientState.sendTemplate(DraftTemplates.BATH)));
        top += 24;
        buttons.add(new UiButton(right, top, 116, 20,
                Component.translatable("template.eclipse.basin"),
                () -> StonemasonClientState.sendTemplate(DraftTemplates.BASIN)));
        top += 24;
        buttons.add(new UiButton(right, top, 116, 20,
                Component.translatable("template.eclipse.column"),
                () -> StonemasonClientState.sendTemplate(DraftTemplates.COLUMN)));
        top += 24;
        UiButton tool = new UiButton(right, top, 116, 20, toolLabel(), () -> {
        });
        tool.action = () -> {
            erasing = !erasing;
            tool.label = toolLabel();
        };
        buttons.add(tool);
        top += 24;
        buttons.add(new UiButton(right, top, 116, 20,
                Component.translatable("screen.eclipse.stonemason_clear"),
                () -> StonemasonClientState.sendClear()));
    }

    private Component toolLabel() {
        return Component.translatable(erasing
                ? "screen.eclipse.stonemason_eraser" : "screen.eclipse.stonemason_pencil");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x68000000);
        DraftMask draft = StonemasonClientState.draft();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int cell = DraftMask.index(x, layer, z);
                int color = draft.get(cell) || stroke.get(cell)
                        ? 0x88FF3B30 : 0xFF8A8D94;
                graphics.fill(gridLeft + x * CELL_PX, gridTop + z * CELL_PX,
                        gridLeft + (x + 1) * CELL_PX - 1, gridTop + (z + 1) * CELL_PX - 1, color);
            }
        }
        for (UiButton button : buttons) {
            boolean hovered = button.contains(mouseX, mouseY);
            graphics.fill(button.x, button.y, button.x + button.w, button.y + button.h,
                    hovered ? 0xE02B2118 : 0xC016120F);
            graphics.fill(button.x, button.y, button.x + button.w, button.y + 2,
                    hovered ? 0xFFE3C099 : 0xAA8A765C);
            graphics.centeredText(font, button.label.getString(),
                    button.x + button.w / 2, button.y + 6, hovered ? 0xFFFFF4DE : 0xFFE3C099);
        }
        graphics.text(font, Component.translatable("screen.eclipse.stonemason_layer", layer).getString(),
                gridLeft, gridTop + GRID_PX + 6, 0xFFFFFFFF);
        String estimate = StonemasonClientState.estimateCells() + " | ~"
                + Math.round(StonemasonClientState.estimateSeconds()) + "s | "
                + Math.round(StonemasonClientState.estimateStamina()) + "%";
        graphics.text(font, estimate, gridLeft, gridTop + GRID_PX + 18, 0xFFE0B36A);
        graphics.text(font, Component.translatable("screen.eclipse.stonemason_hint").getString(),
                gridLeft, gridTop + GRID_PX + 30, 0xFFB0B0B0);
        graphics.text(font,
                Component.translatable("screen.eclipse.stonemason_material",
                        StonemasonClientState.materialId()).getString(),
                gridLeft, gridTop - 14, 0xFFD8D8D8);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (UiButton button : buttons) {
            if (button.contains(event.x(), event.y())) {
                button.action.run();
                return true;
            }
        }
        int cell = cellAt(event.x(), event.y());
        if (event.button() == BUTTON_LEFT && cell >= 0) {
            painting = true;
            paint(cell);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        int cell = cellAt(event.x(), event.y());
        if (painting && event.button() == BUTTON_LEFT && cell >= 0) {
            paint(cell);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (painting && event.button() == BUTTON_LEFT) {
            painting = false;
            flushStroke();
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == KEY_SPACE) {
            StonemasonClientState.sendApprove();
            return true;
        }
        if (event.key() == KEY_ESCAPE) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        if (painting) {
            painting = false;
            flushStroke();
        }
        if (StonemasonClientState.designing()) {
            StonemasonClientState.sendCancel();
        }
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void flushStroke() {
        if (!stroke.isEmpty()) {
            StonemasonClientState.sendStroke(!erasing, stroke);
            stroke.clearAll();
        }
    }

    private void paint(int cell) {
        if (erasing) stroke.clear(cell);
        else stroke.set(cell);
    }

    private int cellAt(double mouseX, double mouseY) {
        int x = (int) Math.floor((mouseX - gridLeft) / CELL_PX);
        int z = (int) Math.floor((mouseY - gridTop) / CELL_PX);
        if (x < 0 || x > 15 || z < 0 || z > 15) return -1;
        return DraftMask.index(x, layer, z);
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
