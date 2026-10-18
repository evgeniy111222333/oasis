package ua.rp.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.Entity;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import ua.rp.chat.client.appearance.EclipseTextureAsset;
import ua.rp.chat.client.tab.TabPortraits;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.client.AcquaintanceClientState;
import ua.rp.chat.client.AcquaintancePayload;
import ua.rp.chat.client.tab.EclipseTabRenderer;
import ua.rp.chat.client.tab.TabLayout;
import ua.rp.chat.client.tab.AstrolabeText;
import com.mojang.blaze3d.textures.FilterMode;

import java.lang.reflect.Field;
import java.util.UUID;

/** Optional end-to-end test with actual Minecraft fonts, GPU GUI, mixins and input events. */
public final class CompactTabClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // The embedded-browser dependency can temporarily replace any screen on first boot.
        context.waitTicks(3);
        context.waitFor(client -> com.cinemamod.mcef.MCEF.isInitialized(), 3600);
        try (TestSingleplayerContext world = createWorld(context)) {
            world.getClientLevel().waitForChunksRender();
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                addCharacterFace(client);
                roster(client, 12, false);
            });
            context.getInput().holdKey(options -> options.keyPlayerList);
            context.waitTicks(3);
            context.takeScreenshot("astrolabe-opening");
            context.waitTicks(21);
            require(context.computeOnClient(EclipseTabRenderer::isVisible), "Holding player-list key must open TAB");
            context.runOnClient(client -> {
                require(layout().columns() == 2 && layout().rows() == 5 && layout().visible() == 10,
                        "Expected original five portraits on each of the astrolabe's two arcs");
                require(Identifier.fromNamespaceAndPath("eclipseclient", "test/astrolabe_character").equals(portraitTexture(new UUID(0, 4))),
                        "TAB must use the character's real custom face texture, including the hat layer");
                require(portraitTexture(client.player.getUUID()).equals(client.player.getSkin().body().texturePath()),
                        "Own face must match the actual world character skin");
                Object cached = rendererField("names");
                roster(client, 12, false);
                require(rendererField("names") == cached, "An identical roster must preserve cached geometry and text");
            });
            context.takeScreenshot("astrolabe-12-russian-real-faces");

            context.getInput().resizeWindow(1920, 1080);
            context.waitTicks(5);
            context.runOnClient(client -> {
                require(layout().radius() == 265, "Full-HD assembly must match the supplied HTML's 530px outer circle");
                AstrolabeText.Label header = (AstrolabeText.Label)rendererField("countText");
                require(header.inkHeight() >= 12, "The Full-HD header must be readable rather than eight-pixel lettering");
                var sampler = client.getTextureManager().getTexture(header.texture()).getSampler();
                require(sampler.getMinFilter() == FilterMode.LINEAR && sampler.getMagFilter() == FilterMode.LINEAR,
                        "The real GPU text sampler must smooth scaled and tilted glyphs");
                require(AstrolabeText.family().equals("Source Serif 4"), "Every player must use the same bundled readable font");
            });
            context.takeScreenshot("astrolabe-full-hd-smooth-text");
            long uploads = context.computeOnClient(client -> AstrolabeText.uploads());
            context.waitTicks(20);
            require(context.computeOnClient(client -> AstrolabeText.uploads()) == uploads,
                    "A stable visible TAB must not rasterize or upload text each frame");
            context.runOnClient(client -> { roster(client, 1, false); client.player.setXRot(55); });
            context.waitTicks(4);
            context.takeScreenshot("astrolabe-readable-single-player-grass");
            context.runOnClient(client -> { roster(client, 12, false); client.player.setXRot(0); });
            context.getInput().resizeWindow(1280, 720);
            context.waitTicks(4);

            context.runOnClient(client -> roster(client, 128, true));
            context.waitTicks(3);
            int slot = context.computeOnClient(client -> client.player.getInventory().getSelectedSlot());
            context.getInput().scroll(-1);
            context.waitTicks(3);
            context.runOnClient(client -> {
                require(layout().first() == 1, "Wheel must move the orbital roster by one character");
                require(client.player.getInventory().getSelectedSlot() == slot, "TAB wheel must preserve hotbar slot");
            });
            context.takeScreenshot("astrolabe-128-long-names-scroll");
            context.getInput().pressKey(GLFW.GLFW_KEY_PAGE_DOWN);
            context.waitTicks(2);
            context.runOnClient(client -> require(layout().first() == 11, "PgDn must jump one visible orbit"));
            context.getInput().pressKey(GLFW.GLFW_KEY_PAGE_UP);
            context.waitTicks(2);
            context.runOnClient(client -> require(layout().first() == 1, "PgUp must reverse the visible-orbit jump"));

            context.runOnClient(client -> { client.options.guiScale().set(4); client.resizeGui(); });
            context.waitTicks(4);
            context.takeScreenshot("astrolabe-large-gui-scale");
            context.runOnClient(client -> roster(client, 1000, true));
            context.waitTicks(3);
            for (int i = 0; i < 75; i++) {
                context.getInput().pressKey(GLFW.GLFW_KEY_PAGE_DOWN);
                context.waitTicks(1);
            }
            context.runOnClient(client -> {
                require(layout().visible() == 10, "A thousand online characters must keep the native render bounded to ten portraits");
                require(AstrolabeText.cachedLabels() <= 64 && AstrolabeText.cachedPixels() <= 4_194_304,
                        "Changing orbits must evict unused text textures and bound GPU memory");
            });
            context.takeScreenshot("astrolabe-1000-bounded-cache");
            context.getInput().resizeWindow(3840, 2160);
            context.waitTicks(5);
            context.runOnClient(client -> {
                require(layout().radius() == 530 && layout().nameScale() == 2,
                        "4K must retain readable text at twice the reference size");
                require(AstrolabeText.cachedLabels() <= 64 && AstrolabeText.cachedPixels() <= 4_194_304,
                        "Even active long 4K labels must fit the sixteen-megabyte text-cache budget");
            });
            context.takeScreenshot("astrolabe-4k-smooth-text");
            context.getInput().resizeWindow(1280, 720);
            context.waitTicks(3);
            context.runOnClient(client -> roster(client, 1, false));
            context.waitTicks(2);
            context.runOnClient(client -> require(layout().page() == 0 && layout().pages() == 1, "Online shrink must clamp page"));
            context.takeScreenshot("astrolabe-single-player");
            context.runOnClient(client -> roster(client, 0, false));
            context.waitTicks(2);
            context.runOnClient(client -> require(layout().visible() == 0, "An empty authoritative roster must show no placeholder characters"));
            context.takeScreenshot("astrolabe-empty-roster");

            context.getInput().releaseKey(options -> options.keyPlayerList);
            context.waitTicks(3);
            require(context.computeOnClient(EclipseTabRenderer::isVisible), "Release must retain the animated disappearance");
            context.takeScreenshot("astrolabe-closing");
            context.waitTicks(14);
            require(!context.computeOnClient(EclipseTabRenderer::isVisible), "Release must finish fade-out");
            var reload = context.computeOnClient(Minecraft::reloadResourcePacks);
            context.waitFor(client -> reload.isDone() && client.getOverlay() == null);
            require(!reload.isCompletedExceptionally(), "Resource reload must complete");
            context.getInput().holdKey(options -> options.keyPlayerList);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); roster(client, 12, false); });
            context.waitTicks(23);
            context.takeScreenshot("astrolabe-after-resource-reload");
            context.runOnClient(client -> require(Identifier.fromNamespaceAndPath("eclipseclient", "test/astrolabe_character").equals(
                    portraitTexture(new UUID(0, 4))), "Custom portraits must recover after resource reload"));
            context.setScreen(() -> new PauseScreen(false));
            require(!context.computeOnClient(EclipseTabRenderer::isVisible), "Screen must immediately suppress TAB");
            context.getInput().releaseKey(options -> options.keyPlayerList);
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                AcquaintanceClientState.reset();
                client.level.removeEntity(1000001, Entity.RemovalReason.DISCARDED);
                client.getTextureManager().release(Identifier.fromNamespaceAndPath("eclipseclient", "test/astrolabe_character"));
            });
        }
    }

    private static void addCharacterFace(Minecraft client) {
        Identifier texture = Identifier.fromNamespaceAndPath("eclipseclient", "test/astrolabe_character");
        NativeImage image = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setPixel(x, y, 0);
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) image.setPixel(x, y, y < 10 ? 0xFF352314 : 0xFFE3B68A);
        image.setPixel(10, 12, 0xFF225533); image.setPixel(13, 12, 0xFF225533);
        for (int x = 40; x < 48; x++) image.setPixel(x, 9, 0xFFD8B467);
        client.getTextureManager().register(texture, new DynamicTexture(() -> "Astrolabe native test character", image));
        PlayerSkin skin = PlayerSkin.insecure(new EclipseTextureAsset(texture), null, null, PlayerModelType.WIDE);
        RemotePlayer character = new RemotePlayer(client.level, new GameProfile(new UUID(0, 4), "NativeCharacter")) {
            @Override public PlayerSkin getSkin() { return skin; }
        };
        character.setId(1000001); character.setPos(client.player.getX() + 30, client.player.getY(), client.player.getZ());
        client.level.addEntity(character);
    }

    @SuppressWarnings("unchecked")
    private static Identifier portraitTexture(UUID uuid) {
        try {
            Field field = TabPortraits.class.getDeclaredField("TEXTURES"); field.setAccessible(true);
            return ((java.util.Map<UUID, Identifier>)field.get(null)).get(uuid);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object rendererField(String name) {
        try { Field field = EclipseTabRenderer.class.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static TestSingleplayerContext createWorld(ClientGameTestContext context) {
        try {
            return context.worldBuilder().create();
        } catch (AssertionError failure) {
            System.err.println("TAB test world setup screen: " + context.computeOnClient(client ->
                    client.screen == null ? "null" : client.screen.getClass().getName()));
            context.takeScreenshot("tab-world-setup-failure");
            throw failure;
        }
    }

    private static TabLayout layout() {
        try {
            Field field = EclipseTabRenderer.class.getDeclaredField("layout");
            field.setAccessible(true);
            return (TabLayout) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to inspect TAB geometry", failure);
        }
    }

    private static void roster(Minecraft client, int count, boolean longNames) {
        String[] names = {"Остап", "Элиан", "Ровена", "Марек", "Ингрид", "Альрик", "Торен", "Аделина", "Ронан", "Северин", "Мира", "Фальк"};
        JsonArray tab = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", (i == 0 ? client.player.getUUID() : new UUID(0, i)).toString());
            entry.addProperty("label", count == 1 ? "Иван Петрович" : longNames ? "Аделина из далёких северных земель " + i : names[i % names.length]);
            entry.addProperty("self", i == 0);
            tab.add(entry);
        }
        JsonObject state = new JsonObject();
        state.addProperty("type", "state");
        state.add("players", new JsonArray());
        state.add("tab", tab);
        AcquaintanceClientState.handle(new AcquaintancePayload(state.toString()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

}
