package ua.rp.chat.client.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import ua.rp.chat.client.appearance.EclipseAppearanceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Current-page face textures. All skin resolution happens outside the drawing path. */
public final class TabPortraits {
    private static final int MAX_PORTRAITS = 12;
    private static final long REFRESH_NANOS = 500_000_000L;
    private static final RefreshWindow REFRESH = new RefreshWindow();
    private static final Map<UUID, Identifier> TEXTURES = new HashMap<>(MAX_PORTRAITS);
    private static ClientLevel currentLevel;

    private TabPortraits() { }

    /** Called only while TAB is visible; an unchanged page performs no skin work between polls. */
    public static void update(Minecraft client, List<UUID> visible, long nowNanos) {
        if (client == null || client.player == null || client.level == null || client.getConnection() == null) {
            reset();
            return;
        }
        if (currentLevel != client.level) {
            reset();
            currentLevel = client.level;
        }
        if (!REFRESH.begin(visible, nowNanos)) return;

        // Replace the page rather than accumulating every player encountered while paging.
        TEXTURES.clear();
        for (UUID uuid : REFRESH.visible()) {
            if (uuid == null || TEXTURES.containsKey(uuid)) continue;
            PlayerSkin skin = resolveSkin(client, uuid);
            if (skin == null || skin.body() == null || skin.body().texturePath() == null) {
                skin = DefaultPlayerSkin.get(uuid);
            }
            TEXTURES.put(uuid, skin.body().texturePath());
        }
    }

    private static PlayerSkin resolveSkin(Minecraft client, UUID uuid) {
        // getSkin includes the server character appearance and the existing blood composition mixins.
        if (uuid.equals(client.player.getUUID())) return client.player.getSkin();
        for (AbstractClientPlayer player : client.level.players()) {
            if (uuid.equals(player.getUUID())) return player.getSkin();
        }

        // This API owns async fetching, retries and texture registration; never call it from render.
        PlayerInfo info = client.getConnection().getPlayerInfo(uuid);
        if (info == null) return DefaultPlayerSkin.get(uuid);
        PlayerSkin custom = EclipseAppearanceManager.getSkin(uuid);
        if (custom != null) return custom;
        return info.getSkin();
    }

    /** Draws the native eight-pixel face and transparent hat, using the renderer's opacity. */
    public static void render(GuiGraphicsExtractor graphics, UUID id, int x, int y, int size, int argb) {
        if (graphics == null || id == null || size <= 0 || (argb >>> 24) == 0) return;
        Identifier texture = TEXTURES.get(id);
        if (texture == null) return;
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8.0f, 8.0f,
                size, size, 8, 8, 64, 64, argb);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40.0f, 8.0f,
                size, size, 8, 8, 64, 64, argb);
    }

    /** Disconnect, world replacement, and TAB lifecycle cleanup; these textures are owned elsewhere. */
    public static void reset() {
        TEXTURES.clear();
        REFRESH.reset();
        currentLevel = null;
    }

    /** Resource reload/appearance changes must not retain an obsolete dynamic texture identifier. */
    public static void invalidate() {
        TEXTURES.clear();
        REFRESH.invalidate();
    }

    /** Bounded refresh scheduling, also runnable without starting Minecraft's native client. */
    static final class RefreshWindow {
        private List<UUID> visible = List.of();
        private long lastRefreshNanos;
        private boolean dirty = true;

        boolean begin(List<UUID> requested, long nowNanos) {
            int count = requested == null ? 0 : Math.min(MAX_PORTRAITS, requested.size());
            boolean changed = visible.size() != count;
            for (int i = 0; !changed && i < count; i++) {
                changed = !Objects.equals(visible.get(i), requested.get(i));
            }
            long elapsed = nowNanos - lastRefreshNanos;
            if (!dirty && !changed && elapsed >= 0 && elapsed < REFRESH_NANOS) return false;
            if (changed) {
                // Copy only the visible prefix; neither huge rosters nor mutable caller lists are retained.
                visible = count == 0 ? List.of() : Collections.unmodifiableList(
                        new ArrayList<>(requested.subList(0, count)));
            }
            lastRefreshNanos = nowNanos;
            dirty = false;
            return true;
        }

        List<UUID> visible() { return visible; }

        void invalidate() { dirty = true; }

        void reset() {
            visible = List.of();
            lastRefreshNanos = 0;
            dirty = true;
        }
    }
}
