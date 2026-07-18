package ua.rp.chat.client.pickup;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Local-only item targeting feedback for deliberate right-click pickup. */
public final class PickupClientState {
    private static final double ITEM_RAY_MARGIN = 0.14;
    private static ItemEntity targetedItem;
    private static String lastItemName = "";
    private static float promptAlpha;

    private PickupClientState() {
    }

    public static void register() {
        // The item is outlined by Minecraft's own entity-outline pass via MinecraftMixin.
        // It follows the rendered 3D model exactly, unlike a collision-box marker.
    }

    public static void clientTick(Minecraft client) {
        targetedItem = findTarget(client);
        if (targetedItem != null) {
            lastItemName = targetedItem.getItem().getHoverName().getString();
        }
        promptAlpha = PickupPromptRules.advanceFade(promptAlpha, targetedItem != null);
    }

    /** Intercepts RMB because dropped items are deliberately not vanilla-pickable entities. */
    public static boolean handleUse(Minecraft client) {
        ItemEntity item = findTarget(client);
        if (item == null) return false;
        targetedItem = item;
        lastItemName = item.getItem().getHoverName().getString();
        if (!ClientPlayNetworking.canSend(ItemPickupPayload.TYPE)) {
            client.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(
                    "Сервер не поддерживает интерактивный подбор."), false);
            return true;
        }
        ClientPlayNetworking.send(new ItemPickupPayload(item.getUUID()));
        return true;
    }

    public static void renderHud(GuiGraphicsExtractor graphics, int width, int height) {
        if (promptAlpha <= 0.01f || lastItemName.isBlank()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.font == null || client.screen != null || targetedItem == null) return;

        Font font = client.font;
        PickupPromptLayout.Layout layout = PickupPromptLayout.forTitle(width, font.width(lastItemName));
        String itemName = fit(font, lastItemName, layout.titleCapacity());
        // Re-run geometry using the rendered (possibly ellipsized) title width. This pins the
        // title's right edge exactly TITLE_TO_MOUSE_GAP pixels before the mouse icon column.
        layout = PickupPromptLayout.forTitle(width, font.width(itemName));
        int cardWidth = layout.cardWidth();
        int x = width / 2 - cardWidth / 2;
        int y = height / 2 + 48;
        float pulse = 0.84f + 0.16f * (float) Math.sin(System.nanoTime() * 0.0000000045d);
        int alpha = Math.round(promptAlpha * 220.0f);
        int accentAlpha = Math.round(alpha * pulse);

        // Quiet, two-level card: the grounded item remains visually clean and the interaction stays readable.
        graphics.fill(x - 2, y - 2, x + cardWidth + 2, y + 38, color(0x020202, alpha / 2));
        graphics.fill(x, y, x + cardWidth, y + 36, color(0x10100F, alpha));
        graphics.fill(x, y, x + cardWidth, y + 1, color(0xD6B77E, accentAlpha));
        graphics.fill(x, y + 35, x + cardWidth, y + 36, color(0x5E4C32, alpha / 2));

        int itemX = x + 10;
        graphics.fill(itemX - 3, y + 8, itemX + 19, y + 30, color(0x201A13, alpha));
        graphics.item(targetedItem.getItem(), itemX, y + 11);
        graphics.verticalLine(x + 35, y + 8, y + 29, color(0x4C3D2B, alpha));

        int textX = x + 45;
        graphics.text(font, itemName, textX, y + 8, color(0xFFF7E9, alpha), false);
        graphics.text(font, "ВЗЯТЬ ПРЕДМЕТ", textX, y + 21, color(0xC5AF8E, accentAlpha), false);

        drawRightClickMouse(graphics, x + layout.mouseX(), y + 8, alpha, accentAlpha);
    }

    private static ItemEntity findTarget(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.screen != null) return null;
        Vec3 eye = client.player.getEyePosition();
        Vec3 direction = client.player.getViewVector(1.0f).normalize();
        double reach = PickupPromptRules.MAX_INTERACTION_DISTANCE;
        Vec3 end = eye.add(direction.scale(reach));
        BlockHitResult blockHit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            reach = Math.min(reach, eye.distanceTo(blockHit.getLocation()) - 0.001);
            if (reach <= 0.0) return null;
            end = eye.add(direction.scale(reach));
        }

        AABB searchBox = client.player.getBoundingBox()
                .expandTowards(direction.scale(reach)).inflate(ITEM_RAY_MARGIN);
        ItemEntity nearest = null;
        double nearestDistanceSquared = reach * reach;
        for (Entity candidate : client.level.getEntities(client.player, searchBox, PickupClientState::isPromptEligible)) {
            if (!(candidate instanceof ItemEntity item)) continue;
            var intersection = item.getBoundingBox().inflate(ITEM_RAY_MARGIN).clip(eye, end);
            if (intersection.isEmpty()) continue;
            double distanceSquared = eye.distanceToSqr(intersection.get());
            if (distanceSquared < nearestDistanceSquared) {
                nearest = item;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    private static boolean isPromptEligible(Entity entity) {
        if (!(entity instanceof ItemEntity item) || !item.isAlive()
                || item.hasPickUpDelay() || item.getItem().isEmpty()) return false;
        Minecraft client = Minecraft.getInstance();
        Entity owner = item.getOwner();
        return owner == null || owner == client.player;
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String suffix = "...";
        int available = Math.max(0, maxWidth - font.width(suffix));
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) > available) end--;
        return text.substring(0, end).stripTrailing() + suffix;
    }

    /**
     * Compact angular mouse glyph (D): it has no enclosing keycap, so it stays optically centred
     * in its action column. Only the upper-right button receives the living gold accent.
     */
    private static void drawRightClickMouse(GuiGraphicsExtractor graphics, int x, int y, int alpha, int accentAlpha) {
        int bodyWidth = PickupPromptLayout.MOUSE_ICON_WIDTH;
        int bodyHeight = 20;
        int border = color(0xF0DFC1, alpha);
        int interior = color(0x16120F, alpha);
        int seam = color(0x70583A, alpha);

        // Five horizontal strips make the small silhouette read as a real angular mouse,
        // rather than as a square button or a floating UI frame.
        graphics.fill(x + 4, y, x + bodyWidth - 4, y + 1, border);
        graphics.fill(x + 2, y + 1, x + bodyWidth - 2, y + 2, border);
        graphics.fill(x + 1, y + 2, x + bodyWidth - 1, y + bodyHeight - 2, border);
        graphics.fill(x + 2, y + bodyHeight - 2, x + bodyWidth - 2, y + bodyHeight - 1, border);
        graphics.fill(x + 4, y + bodyHeight - 1, x + bodyWidth - 4, y + bodyHeight, border);

        graphics.fill(x + 3, y + 3, x + bodyWidth - 3, y + bodyHeight - 3, interior);
        int split = x + bodyWidth / 2;
        graphics.fill(split, y + 3, split + 1, y + 9, seam);
        graphics.fill(x + 3, y + 3, split, y + 9, color(0x30261B, alpha));
        graphics.fill(split + 1, y + 3, x + bodyWidth - 3, y + 9, color(0xD8B773, accentAlpha));
        graphics.fill(x + 3, y + 10, x + bodyWidth - 3, y + 11, seam);

        // Wheel: intentionally lower and centred, leaving the gold right button unambiguous.
        graphics.fill(split - 1, y + 13, split + 2, y + 17, color(0xE7C98F, accentAlpha));
        graphics.fill(split, y + 14, split + 1, y + 16, color(0x5A442B, alpha));
    }

    private static int color(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }
}
