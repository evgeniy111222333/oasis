package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class EscapeRadialScreen extends Screen {
    private int ticks;

    public EscapeRadialScreen() {
        super(Component.literal("Освобождение"));
    }

    @Override public void tick() { ticks++; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        EscapeClientState.Capabilities c = EscapeClientState.capabilities();
        int cx = width / 2;
        int cy = height / 2;
        float reveal = Math.min(1.0f, (ticks + delta) / 8.0f);
        g.fill(0, 0, width, height, 0x76000000);
        int ring = Math.round(82 * reveal);
        for (int i = 0; i < 72; i++) {
            double a = Math.PI * 2.0 * i / 72.0;
            int x = cx + (int) Math.round(Math.cos(a) * ring);
            int y = cy + (int) Math.round(Math.sin(a) * ring);
            int color = i % 9 == 0 ? 0xFFE3C099 : 0x886B5A43;
            g.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
        g.fill(cx - 86, cy - 34, cx + 86, cy + 35, 0xE018140F);
        g.fill(cx - 86, cy - 34, cx + 86, cy - 31, 0xFFE3C099);
        g.centeredText(font, "ПУТЫ НА ЗАПЯСТЬЯХ", cx, cy - 22, 0xFFFFE8C5);
        g.centeredText(font, fit(c.material(), 150), cx, cy - 7, 0xFFA5C3C4);
        int durability = c.maxDurability() <= 0 ? 0 : (int) Math.round(c.durability() / c.maxDurability() * 100.0);
        g.centeredText(font, "прочность " + durability + "%  •  силы " + Math.round(c.stamina()) + "%", cx, cy + 9, 0xFFB7A895);
        g.centeredText(font, "G / Esc — закрыть", cx, cy + 23, 0xFF81776E);

        List<Option> options = options(c);
        for (int i = 0; i < options.size(); i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI * 2.0 / options.size();
            int x = cx + (int) Math.round(Math.cos(angle) * 172.0);
            int y = cy + (int) Math.round(Math.sin(angle) * 105.0);
            Bounds b = Bounds.centered(x, y, 142, 38);
            Option option = options.get(i);
            boolean hover = b.contains(mouseX, mouseY);
            int bg = option.enabled ? (hover ? 0xED32251A : 0xD017130F) : 0xB0100E0C;
            int edge = option.enabled ? (hover ? 0xFFE3C099 : 0xAA78644A) : 0x665E554C;
            g.fill(b.left, b.top, b.right, b.bottom, bg);
            g.fill(b.left, b.top, b.right, b.top + 2, edge);
            g.centeredText(font, option.title, b.cx(), b.top + 7, option.enabled ? 0xFFFFE8C5 : 0xFF746E68);
            g.centeredText(font, option.subtitle, b.cx(), b.top + 22, option.enabled ? 0xFFA5C3C4 : 0xFF5E5954);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        List<Option> options = options(EscapeClientState.capabilities());
        int cx = width / 2;
        int cy = height / 2;
        for (int i = 0; i < options.size(); i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI * 2.0 / options.size();
            Bounds b = Bounds.centered(cx + (int) Math.round(Math.cos(angle) * 172.0), cy + (int) Math.round(Math.sin(angle) * 105.0), 142, 38);
            Option option = options.get(i);
            if (b.contains(event.x(), event.y())) {
                if (option.enabled) {
                    EscapeClientState.action(option.action);
                    onClose();
                }
                return true;
            }
        }
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) { if (event.key() == 256 || event.key() == 71) { onClose(); } return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(null); }

    private List<Option> options(EscapeClientState.Capabilities c) {
        return List.of(
                new Option("Ослабить узлы", "испытание силы", 70, c.canStruggle()),
                new Option("Перерезать путы", "спрятанное лезвие", 72, c.canBlade()),
                new Option("Использовать среду", "камень или огонь", 73, c.canEnvironment()),
                new Option("Позвать на помощь", "громкий крик", 75, true)
        );
    }

    private String fit(String value, int max) { String r = value == null ? "" : value; while (!r.isEmpty() && font.width(r) > max) r = r.substring(0, r.length() - 1); return r; }
    private record Option(String title, String subtitle, int action, boolean enabled) {}
    private record Bounds(int left, int top, int right, int bottom) {
        static Bounds centered(int x, int y, int w, int h) { return new Bounds(x-w/2,y-h/2,x+w/2,y+h/2); }
        boolean contains(double x,double y){return x>=left&&x<=right&&y>=top&&y<=bottom;} int cx(){return(left+right)/2;}
    }
}
