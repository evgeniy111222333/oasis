package ua.rp.chat.client.heavyhammer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Подменю виртуального снаряжения, открываемое из обычного инвентаря. */
public final class HammerEquipmentScreen extends Screen {
    private final Screen parent;

    public HammerEquipmentScreen(Screen parent) {
        super(Component.literal("Снаряжение Eclipse"));
        this.parent = parent;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelWidth = Math.min(470, width - 32);
        int panelHeight = Math.min(260, height - 32);
        int left = width / 2 - panelWidth / 2;
        int top = height / 2 - panelHeight / 2;
        int navigationWidth = Math.min(132, panelWidth / 3);
        int cardLeft = left + navigationWidth + 14;
        int cardRight = left + panelWidth - 14;
        int cardTop = top + 42;
        int cardBottom = top + panelHeight - 48;

        graphics.fill(0, 0, width, height, 0x82000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0181512);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFFE3C099);
        graphics.fill(left, top + 34, left + panelWidth, top + 35, 0xFF4B3829);
        graphics.centeredText(font, "СНАРЯЖЕНИЕ", left + panelWidth / 2, top + 13, 0xFFFFE8C2);

        graphics.fill(left + 10, top + 47, left + navigationWidth - 8, top + 70, 0xFF221C17);
        graphics.text(font, "Персонаж", left + 18, top + 54, 0xFF8D8276);
        graphics.fill(left + 10, top + 76, left + navigationWidth - 8, top + 105, 0xFF3A2B20);
        graphics.fill(left + 10, top + 76, left + 13, top + 105, 0xFFE3C099);
        graphics.text(font, "Подвес молота", left + 18, top + 86, 0xFFFFE8C2);

        graphics.fill(cardLeft, cardTop, cardRight, cardBottom, 0xCC211A15);
        graphics.outline(cardLeft, cardTop, cardRight - cardLeft, cardBottom - cardTop, 0xFF614936);
        int slotX = cardLeft + 18;
        int slotY = cardTop + 26;
        graphics.fill(slotX - 7, slotY - 7, slotX + 39, slotY + 39, 0xFF100E0C);
        graphics.outline(slotX - 7, slotY - 7, 46, 46, 0xFF8C6A49);
        ItemStack stack = HammerHolsterClientState.displayStack(minecraft == null ? null : minecraft.player);
        graphics.pose().pushMatrix();
        graphics.pose().translate(slotX, slotY);
        graphics.pose().scale(2.0f, 2.0f);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();

        boolean owned = minecraft != null && HammerHolsterClientState.hasHolster(minecraft.player);
        boolean equipped = minecraft != null && HammerHolsterClientState.isEquipped(minecraft.player);
        graphics.text(font, HammerHolsterClientState.DISPLAY_NAME, slotX + 52, cardTop + 25,
                owned ? 0xFFFFE8C2 : 0xFF8D8276);
        graphics.text(font, equipped ? "НАДЕТО" : owned ? "В ИНВЕНТАРЕ" : "ОТСУТСТВУЕТ",
                slotX + 52, cardTop + 43, equipped ? 0xFF8ED6A3 : owned ? 0xFFE3C099 : 0xFFC97868);
        graphics.textWithWordWrap(font, Component.literal(
                        "Кожаная портупея удерживает боёк у правого бедра, а древко проходит через две петли."),
                slotX + 52, cardTop + 62, Math.max(90, cardRight - slotX - 66), 0xFFB8ADA1);

        int actionLeft = cardRight - 126;
        int actionTop = cardBottom - 32;
        boolean actionHover = mouseX >= actionLeft && mouseX <= cardRight - 10
                && mouseY >= actionTop && mouseY <= cardBottom - 8;
        graphics.fill(actionLeft, actionTop, cardRight - 10, cardBottom - 8,
                !owned ? 0xFF27221E : actionHover ? 0xFF684A32 : 0xFF4A3526);
        graphics.centeredText(font, equipped ? "Снять" : "Надеть",
                (actionLeft + cardRight - 10) / 2, actionTop + 8, owned ? 0xFFFFE8C2 : 0xFF716A63);
        graphics.centeredText(font, "ПКМ по предмету также надевает или снимает портупею",
                left + panelWidth / 2, top + panelHeight - 25, 0xFF8D8276);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int panelWidth = Math.min(470, width - 32);
        int panelHeight = Math.min(260, height - 32);
        int left = width / 2 - panelWidth / 2;
        int top = height / 2 - panelHeight / 2;
        int navigationWidth = Math.min(132, panelWidth / 3);
        int cardRight = left + panelWidth - 14;
        int cardBottom = top + panelHeight - 48;
        int actionLeft = cardRight - 126;
        int actionTop = cardBottom - 32;
        if (event.x() >= actionLeft && event.x() <= cardRight - 10
                && event.y() >= actionTop && event.y() <= cardBottom - 8
                && minecraft != null && HammerHolsterClientState.hasHolster(minecraft.player)) {
            HammerHolsterClientState.setEquipped(minecraft,
                    !HammerHolsterClientState.isEquipped(minecraft.player), true);
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
