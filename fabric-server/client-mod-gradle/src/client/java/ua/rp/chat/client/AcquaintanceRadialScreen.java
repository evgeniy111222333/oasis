package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AcquaintanceRadialScreen extends Screen {
    private static final int CENTER_W = 184;
    private static final int CENTER_H = 82;
    private static final int OPTION_W = 128;
    private static final int OPTION_H = 34;
    private static final int SUB_W = 154;
    private static final int SAFE_GAP = 28;

    private final UUID targetId;
    private final String targetLabel;
    private final List<Option> roots = new ArrayList<>();
    private int ticksOpen;
    private String activeGroup = "social";

    public AcquaintanceRadialScreen(UUID targetId, String targetLabel) {
        super(Component.literal("Eclipse interaction"));
        this.targetId = targetId;
        this.targetLabel = targetLabel == null ? "Незнакомец" : targetLabel;
        roots.add(new Option("social", "Общение", "знакомство и приметы", 0, "social"));
        roots.add(new Option("control", "Контроль", "руки, оружие, полон", 0, "control"));
        roots.add(new Option("search", "Обыск", "пояс, сумка, одежда", 0, "search"));
        roots.add(new Option("medical", "Медицина", "раны и состояние", 0, "medical"));
        roots.add(new Option("leave", "Отойти", "закрыть меню", -1, null));
    }

    @Override
    public void tick() {
        ticksOpen++;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int cx = width / 2;
        int cy = height / 2;
        float open = Math.min(1.0f, (ticksOpen + delta) / 9.0f);

        graphics.fill(0, 0, width, height, 0x68000000);
        drawCenter(graphics, cx, cy, open);

        List<Placed> placedRoots = rootLayout(cx, cy);
        String hoveredGroup = null;
        for (Placed placed : placedRoots) {
            boolean hovered = placed.bounds.contains(mouseX, mouseY);
            if (hovered && placed.option.group != null) {
                hoveredGroup = placed.option.group;
            }
            drawOption(graphics, placed.bounds, placed.option, hovered || placed.option.group != null && placed.option.group.equals(activeGroup));
        }
        if (hoveredGroup != null) {
            activeGroup = hoveredGroup;
        }

        List<Option> sub = subOptions(activeGroup);
        int subX = cx + CENTER_W / 2 + SAFE_GAP + SUB_W / 2;
        int startY = cy - (sub.size() * (OPTION_H + 7)) / 2 + OPTION_H / 2;
        for (int i = 0; i < sub.size(); i++) {
            Bounds b = Bounds.centered(subX, startY + i * (OPTION_H + 7), SUB_W, OPTION_H);
            Option option = sub.get(i);
            boolean hovered = b.contains(mouseX, mouseY);
            drawOption(graphics, b, option, hovered);
            if (hovered && option.group != null) {
                activeGroup = option.group;
            }
        }
    }

    private List<Placed> rootLayout(int cx, int cy) {
        int leftX = cx - CENTER_W / 2 - SAFE_GAP - OPTION_W / 2;
        int topY = cy - 102;
        int step = 48;
        List<Placed> result = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            result.add(new Placed(roots.get(i), Bounds.centered(leftX, topY + i * step, OPTION_W, OPTION_H)));
        }
        return result;
    }

    private List<Option> subOptions(String group) {
        List<Option> list = new ArrayList<>();
        switch (group) {
            case "control" -> {
                if (EscapeClientState.isBound()) {
                    list.add(new Option("help_bound", "Помочь пленнику", "разобрать узлы зубами", 76, null));
                }
                list.add(new Option("bind", "Связать", "веревка / цепь / ремень", 11, null));
                list.add(new Option("unbind", "Развязать", "освободить руки", 12, null));
                list.add(new Option("disarm", "Снять оружие", "из рук или пояса", 18, null));
                list.add(new Option("carry", "Тащить", "поднять или потянуть", 19, null));
                list.add(new Option("release", "Отпустить", "прекратить удержание", 20, null));
                list.add(new Option("force", "Принудить", "силовой захват без согласия", 0, "force"));
            }
            case "search" -> {
                list.add(new Option("quick", "Быстрый", "оружие и очевидное", 13, null));
                list.add(new Option("hands", "Руки", "ладони и рукава", 14, null));
                list.add(new Option("belt", "Пояс", "ножны, кошель, ключи", 15, null));
                list.add(new Option("bag", "Сумка", "вещи внутри", 16, null));
                list.add(new Option("cloak", "Одежда", "плащ, обувь, тайники", 17, null));
                list.add(new Option("thorough", "Тщательно", "полный обыск под контролем", 22, null));
            }
            case "medical" -> list.add(new Option("wounds", "Осмотреть раны", "кровь, кости, дыхание", 10, null));
            case "force" -> {
                list.add(new Option("force_control", "Удержание", "связать, обезоружить, переместить", 0, "force_control"));
                list.add(new Option("force_search", "Обыск силой", "перехватить и проверить вещи", 0, "force_search"));
                list.add(new Option("force_inspect", "Осмотреть раны", "зафиксировать для осмотра", 50, null));
                list.add(new Option("back_control", "Назад", "к добровольным действиям", 0, "control"));
            }
            case "force_control" -> {
                list.add(new Option("force_bind", "Связать силой", "перехватить руки", 51, null));
                list.add(new Option("force_disarm", "Обезоружить", "вырвать оружие", 58, null));
                list.add(new Option("force_carry", "Утащить", "сломать опору и повести", 59, null));
                list.add(new Option("back_force", "Назад", "к силовым действиям", 0, "force"));
            }
            case "force_search" -> {
                list.add(new Option("force_quick", "Быстрый", "оружие и очевидное", 53, null));
                list.add(new Option("force_hands", "Руки", "ладони и рукава", 54, null));
                list.add(new Option("force_belt", "Пояс", "ножны, кошель, ключи", 55, null));
                list.add(new Option("force_bag", "Сумка", "вещи внутри", 56, null));
                list.add(new Option("force_cloak", "Одежда", "плащ, обувь, тайники", 57, null));
                list.add(new Option("force_thorough", "Тщательно", "полный обыск под контролем", 62, null));
                list.add(new Option("back_force", "Назад", "к силовым действиям", 0, "force"));
            }
            default -> {
                list.add(new Option("greet", "Приветствие", "открытые ладони", 1, null));
                list.add(new Option("note", "Записать", "личную примету", 5, null));
            }
        }
        return list;
    }

    private void drawCenter(GuiGraphicsExtractor graphics, int cx, int cy, float open) {
        int halfW = Math.round((CENTER_W / 2.0f) * open);
        int halfH = Math.round((CENTER_H / 2.0f) * open);
        int left = cx - halfW;
        int right = cx + halfW;
        int top = cy - halfH;
        int bottom = cy + halfH;
        graphics.fill(left, top, right, bottom, 0xD018140F);
        graphics.fill(left, top, right, top + 3, 0xCCE3C099);
        graphics.fill(left, bottom - 3, right, bottom, 0x88513F2F);
        graphics.fill(left, top, left + 3, bottom, 0x996B5A43);
        graphics.fill(right - 3, top, right, bottom, 0x996B5A43);
        graphics.centeredText(font, fit(targetLabel, 154), cx, cy - 14, 0xFFE3C099);
        graphics.centeredText(font, groupTitle(activeGroup), cx, cy + 4, 0xFFA5C3C4);
        graphics.centeredText(font, "Esc - закрыть", cx, cy + 20, 0xFF9A9289);
    }

    private String groupTitle(String group) {
        return switch (group) {
            case "control" -> "физический контроль";
            case "search" -> "обыск по зонам";
            case "medical" -> "осмотр состояния";
            case "force" -> "принудительное действие";
            case "force_control" -> "силовое удержание";
            case "force_search" -> "обыск без согласия";
            default -> "социальные действия";
        };
    }

    private void drawOption(GuiGraphicsExtractor graphics, Bounds b, Option option, boolean hovered) {
        int bg = hovered ? 0xE02B2118 : 0xC016120F;
        int edge = hovered ? 0xFFE3C099 : 0xAA8A765C;
        graphics.fill(b.left, b.top, b.right, b.bottom, bg);
        graphics.fill(b.left, b.top, b.right, b.top + 2, edge);
        graphics.fill(b.left, b.bottom - 2, b.right, b.bottom, 0x88513F2F);
        graphics.centeredText(font, option.title, b.cx(), b.top + 6, hovered ? 0xFFFFF4DE : 0xFFE3C099);
        graphics.centeredText(font, fit(option.sub, b.width() - 12), b.cx(), b.top + 20, 0xFF9A9289);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Placed placed : rootLayout(width / 2, height / 2)) {
            if (placed.bounds.contains(event.x(), event.y())) {
                if (placed.option.action == -1) {
                    onClose();
                } else if (placed.option.group != null) {
                    activeGroup = placed.option.group;
                }
                return true;
            }
        }

        List<Option> sub = subOptions(activeGroup);
        int subX = width / 2 + CENTER_W / 2 + SAFE_GAP + SUB_W / 2;
        int startY = height / 2 - (sub.size() * (OPTION_H + 7)) / 2 + OPTION_H / 2;
        for (int i = 0; i < sub.size(); i++) {
            Bounds b = Bounds.centered(subX, startY + i * (OPTION_H + 7), SUB_W, OPTION_H);
            if (b.contains(event.x(), event.y())) {
                trigger(sub.get(i));
                return true;
            }
        }
        return true;
    }

    private void trigger(Option option) {
        if (option.group != null) {
            activeGroup = option.group;
            return;
        }
        if (option.action == 5) {
            AcquaintanceClientState.openNote(targetId, targetLabel);
            return;
        }
        AcquaintanceClientState.interaction(targetId, option.action);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        String trimmed = value;
        while (!trimmed.isEmpty() && font.width(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? ellipsis : trimmed + ellipsis;
    }

    private record Option(String id, String title, String sub, int action, String group) {
    }

    private record Placed(Option option, Bounds bounds) {
    }

    private record Bounds(int left, int top, int right, int bottom) {
        static Bounds centered(int cx, int cy, int width, int height) {
            return new Bounds(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
        }

        boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        int width() {
            return right - left;
        }

        int cx() {
            return (left + right) / 2;
        }
    }
}
