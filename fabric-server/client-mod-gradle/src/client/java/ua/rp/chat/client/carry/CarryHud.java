package ua.rp.chat.client.carry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Original concept-01 artwork; only its lower transparent mouse/action region is sampled. */
public final class CarryHud {
    private static final Identifier ASSET = Identifier.fromNamespaceAndPath("eclipseclient", "textures/gui/carry/concept_01.png");
    private CarryHud() {}
    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft client = Minecraft.getInstance();
        boolean visible = client.screen == null && !client.options.hideGui && CarryClient.focus() != null;
        var sample = CarryClient.FEEDBACK.sample(visible, CarryClient.seconds());
        if (!CarryClient.supported() || client.screen != null || client.options.hideGui) return;
        if (sample.alpha() > .005 && !CarryClient.lastName().isBlank()) {
            int w = Math.min(122, Math.max(72, width - 28));
            int h = Math.round(w * (CarryFeedback.SOURCE_HEIGHT / (float) CarryFeedback.SOURCE_WIDTH));
            float cx = width / 2f, cy = height / 2f + 38 + sample.lift();
            graphics.pose().pushMatrix();
            graphics.pose().translate(cx, cy).scale(sample.scale());
            graphics.blit(RenderPipelines.GUI_TEXTURED, ASSET, -w / 2, 0, CarryFeedback.U, CarryFeedback.V,
                    w, h, CarryFeedback.SOURCE_WIDTH, CarryFeedback.SOURCE_HEIGHT,
                    CarryFeedback.TEXTURE_SIZE, CarryFeedback.TEXTURE_SIZE, color(0xFFFFFF, sample.alpha()));
            graphics.pose().popMatrix();
            String name = client.font.plainSubstrByWidth(CarryClient.lastName(), Math.max(30, width - 30));
            graphics.centeredText(client.font, name, width / 2, height / 2 + 23, color(0xFFF0D7, sample.alpha()));
            if (CarryClient.focus() != null && CarryClient.focus().block() != null)
                graphics.centeredText(client.font, "Shift + ПКМ · підняти цілим", width / 2, (int) cy + h + 3, color(0xD8C4A3, sample.alpha()));
        }
        var held = CarryClientState.local();
        if (held != null) {
            String hint = CarryClient.placing()
                    ? "ПКМ · поставити   ← / → · повернути   ЛКМ · скасувати"
                    : (held.large() ? "Несете двома руками" : "У долоні") + " · " + held.name() + "   [V] дії";
            int y = Math.min(height - 46, height / 2 + 98);
            graphics.centeredText(client.font, client.font.plainSubstrByWidth(hint, width - 16), width / 2, y, 0xFFF2DFC1);
            if (CarryClient.placing()) {
                var preview = CarryWorldFeedback.preview();
                graphics.centeredText(client.font, preview != null && preview.valid() ? "Можна поставити · " + CarryClient.yaw() + "°" : "Потрібна вільна поверхня поруч",
                        width / 2, y + 13, preview != null && preview.valid() ? 0xFFB5DEB0 : 0xFFE7A39C);
            }
        }
        var offer = CarryClientState.incoming();
        if (offer != null) graphics.centeredText(client.font,
                "Вам пропонують: " + client.font.plainSubstrByWidth(offer.name(), Math.max(30, width - 160)) + "   [V] прийняти / відхилити",
                width / 2, height / 2 - 43, 0xFFFFE6B8);
    }
    private static int color(int rgb, float alpha) { return Math.round(Math.clamp(alpha, 0, 1) * 255) << 24 | rgb; }
}
