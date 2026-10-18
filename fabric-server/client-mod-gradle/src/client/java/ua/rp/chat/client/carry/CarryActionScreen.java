package ua.rp.chat.client.carry;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Consent and placement actions; the player keeps looking at the scene behind this compact menu. */
public final class CarryActionScreen extends Screen {
    private int row, left, buttonWidth;
    public CarryActionScreen() { super(Component.literal("Предмет у руках")); }
    @Override protected void init() {
        row = 0; buttonWidth = Math.min(240, width - 32); left = (width - buttonWidth) / 2;
        var held = CarryClientState.local();
        if (CarryClientState.incoming() != null) {
            add("Прийняти запропонований предмет", () -> CarryClient.respond(true), true);
            add("Відхилити пропозицію", () -> CarryClient.respond(false), true);
        }
        if (held == null) {
            add("Тримати вибраний предмет у долоні", () -> CarryClient.action("hold"), !minecraft.player.getMainHandItem().isEmpty());
        } else {
            add(CarryClient.placing() ? "Продовжити встановлення" : "Поставити · попередній перегляд", CarryClient::startPlacement, true);
            add("Показати предмет", () -> CarryClient.action("show"), !held.large());
            add("Запропонувати гравцю перед вами", CarryClient::offer, CarryTargeting.player(minecraft) != null);
            add("Прибрати в інвентар", () -> CarryClient.action("stow"), held.stowable());
            add("Опустити біля себе", () -> CarryClient.action("drop"), true);
        }
        add("Повернутися", () -> {}, true);
    }
    private void add(String label, Runnable action, boolean enabled) {
        int count = CarryClientState.local() == null ? 2 : 6;
        if (CarryClientState.incoming() != null) count += 2;
        int top = Math.max(42, (height - count * 24) / 2 + 12);
        Button button = Button.builder(Component.literal(label), pressed -> { minecraft.setScreen(null); action.run(); })
                .bounds(left, top + row++ * 24, buttonWidth, 20).build();
        button.active = enabled; addRenderableWidget(button);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left - 10, 15, left + buttonWidth + 10, height - 15, 0xDA15120E);
        graphics.centeredText(font, title, width / 2, 24, 0xFFFFE6BE);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}
}
