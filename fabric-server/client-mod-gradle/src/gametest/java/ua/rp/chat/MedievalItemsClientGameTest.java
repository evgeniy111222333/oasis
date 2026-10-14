package ua.rp.chat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import ua.rp.chat.carver.CarverItems;
import ua.rp.chat.item.MedievalItems;

import java.lang.reflect.Field;
import java.util.List;

/** Real registry, baked atlas sprites, exact geometry, all display contexts and resource reload. */
public final class MedievalItemsClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(3);
        context.waitFor(client -> com.cinemamod.mcef.MCEF.isInitialized(), 3600);
        try (var world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                ItemStack hammer = new ItemStack(MedievalItems.HAMMER);
                require(hammer.getMaxStackSize() == 1 && hammer.getMaxDamage() == 180, "Hammer properties");
                require(hammer.get(DataComponents.WEAPON).itemDamagePerAttack() == 1, "Hammer durability on attack");
                Item[] items = {MedievalItems.HAMMER, CarverItems.CHISEL_POINT, CarverItems.CHISEL_FLAT,
                        CarverItems.BAG, CarverItems.BAG_NO_POINT, CarverItems.BAG_EMPTY};
                int[] counts = {978, 288, 264, 1650, 1362, 600};
                for (int i = 0; i < items.length; i++) {
                    for (ItemDisplayContext display : ItemDisplayContext.values()) {
                        ItemStackRenderState state = new ItemStackRenderState();
                        client.getItemModelResolver().updateForLiving(state, new ItemStack(items[i]), display, client.player);
                        require(!state.isEmpty(), "Missing item model: " + items[i] + " / " + display);
                        require(quads(state) == counts[i], "Wrong authored quad count: " + items[i] + " / " + display);
                        verifyAuthoredVertices(client, state, items[i]);
                        require(state.getModelBoundingBox().getSize() > 0, "Missing authored model extents");
                    }
                }
                client.player.setItemSlot(EquipmentSlot.MAINHAND, hammer);
                client.player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(CarverItems.CHISEL_POINT));
                client.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(CarverItems.BAG));
            });
            context.waitTicks(10);
            context.takeScreenshot("medieval-items-first-person");
            context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
            context.waitTicks(10);
            context.takeScreenshot("medieval-items-new-bag-on-back");
            var reload = context.computeOnClient(net.minecraft.client.Minecraft::reloadResourcePacks);
            context.waitFor(client -> reload.isDone() && client.getOverlay() == null);
            require(!reload.isCompletedExceptionally(), "Authored item resource reload failed");
            context.runOnClient(client -> {
                ItemStackRenderState state = new ItemStackRenderState();
                client.getItemModelResolver().updateForLiving(state, new ItemStack(MedievalItems.HAMMER),
                        ItemDisplayContext.GUI, client.player);
                require(quads(state) == 978, "Reload lost the exact hammer mesh");
            });
        }
    }

    private static int quads(ItemStackRenderState state) {
        try {
            Field layers = ItemStackRenderState.class.getDeclaredField("layers");
            Field count = ItemStackRenderState.class.getDeclaredField("activeLayerCount");
            layers.setAccessible(true); count.setAccessible(true);
            var values = (ItemStackRenderState.LayerRenderState[]) layers.get(state);
            int total = 0;
            for (int i = 0; i < count.getInt(state); i++) total += values[i].prepareQuadList().size();
            return total;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void verifyAuthoredVertices(net.minecraft.client.Minecraft client, ItemStackRenderState state, Item item) {
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        try (var reader = new java.io.InputStreamReader(client.getResourceManager().getResource(
                Identifier.fromNamespaceAndPath(id.getNamespace(), "models/item/" + id.getPath() + ".json"))
                .orElseThrow().open(), java.nio.charset.StandardCharsets.UTF_8)) {
            var expected = ua.rp.chat.client.render.BlockbenchItemGeometry.decode(
                    JsonParser.parseReader(reader).getAsJsonObject());
            Field field = ItemStackRenderState.class.getDeclaredField("layers");
            field.setAccessible(true);
            var layer = ((ItemStackRenderState.LayerRenderState[]) field.get(state))[0];
            var actual = layer.prepareQuadList();
            for (int i = 0; i < actual.size(); i++) {
                for (int j = 0; j < 4; j++) require(actual.get(i).position(j)
                                .distance(expected.faces().get(i).positions()[j]) < 1e-6,
                        "Runtime lost an authored cube rotation: " + item);
                require(!actual.get(i).materialInfo().sprite().contents().name().getPath().contains("missing"),
                        "Missing texture for " + item);
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
