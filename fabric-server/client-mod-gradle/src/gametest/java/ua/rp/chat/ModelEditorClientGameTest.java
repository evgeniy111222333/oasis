package ua.rp.chat;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ua.rp.chat.carver.CarverItems;
import ua.rp.chat.client.modeleditor.*;
import ua.rp.chat.item.MedievalItems;
import ua.rp.chat.modeleditor.*;

import java.lang.reflect.Field;
import java.util.List;

/** Real GUI input, detached avatar rendering, imported geometry and all presentation contexts. */
public final class ModelEditorClientGameTest implements FabricClientGameTest {
    private static final int PREVIEW_ID = -2_000_000_026;
    @Override public void runTest(ClientGameTestContext context) {
        context.waitTicks(3);
        context.waitFor(client -> com.cinemamod.mcef.MCEF.isInitialized(), 3600);
        try (var world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.getInput().resizeWindow(1280, 720);
            verifyEquippedSubject(context);
            context.runOnClient(client -> {
                client.options.guiScale().set(2); client.resizeGui();
                client.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(CarverItems.BAG));
                client.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(MedievalItems.HAMMER));
                JsonObject envelope = new JsonObject();
                envelope.addProperty("type", "open"); envelope.addProperty("editable", true);
                envelope.addProperty("message", "Редактор готов");
                envelope.add("document", JsonParser.parseString(ModelEditorDocument.empty().encode()));
                ModelEditorClient.accept(envelope.toString());
                require(client.screen instanceof ModelEditorScreen, "Open response must show the editor");
                field(client.screen, "ID предмета").setValue("eclipseserver:carver_bag");
                press(client.screen, "Найти");
                require((Object)ModelEditorClient.previewPlayer(PREVIEW_ID) != client.player, "Preview must be detached");
                require(ModelEditorClient.previewPlayer(PREVIEW_ID).getItemBySlot(EquipmentSlot.CHEST).is(CarverItems.BAG), "Selected bag must appear on preview");
                field(client.screen, "Сдвиг X").setValue("2");
                require(ModelEditorClient.placement("eclipseserver:carver_bag", ModelPose.WORN).transform().x() == 2, "Numeric edit must update preview immediately");
                require(ModelEditorClient.authoritativePlacement("eclipseserver:carver_bag", ModelPose.WORN).transform().x() == 0, "Draft must not affect the live world");
                press(client.screen, "Отмена ↶");
                require(ModelEditorClient.dirtyCount() == 0, "Undo must restore default mount");
                press(client.screen, "Повтор ↷");
                require(ModelEditorClient.dirtyCount() == 1, "Redo must retain the edited mount");
                field(client.screen, "Масштаб X").setValue("9");
                require(ModelEditorClient.placement("eclipseserver:carver_bag", ModelPose.WORN).transform().sx() == .79f, "Out-of-range input must not enter rendering");
                field(client.screen, "Масштаб X").setValue("0.79");
            });
            context.waitTicks(6);
            context.takeScreenshot("model-editor-backpack-draft");
            for (String animation : List.of("Ходьба", "Присесть", "Резьба")) {
                context.runOnClient(client -> press(client.screen, animation));
                context.waitTicks(8);
                context.takeScreenshot("model-editor-animation-" + ModelEditorClient.previewAnimation());
            }
            context.runOnClient(client -> {
                require(client.player.getItemBySlot(EquipmentSlot.CHEST).is(CarverItems.BAG), "Editor must preserve the real chest item");
                require(client.player.getMainHandItem().is(MedievalItems.HAMMER), "Editor must preserve the real held item");
                require(!client.player.isShiftKeyDown(), "Preview crouching must not change the real player");
                verifyContexts(client);
                field(client.screen, "ID предмета").setValue("eclipseserver:ancient_peasant_hammer");
                press(client.screen, "Найти");
                chooseContext(client.screen, "1-е лицо: правая");
            });
            context.waitTicks(5);
            context.takeScreenshot("model-editor-first-person-context");
            context.runOnClient(client -> chooseContext(client.screen, "Инвентарь"));
            context.waitTicks(5);
            context.takeScreenshot("model-editor-replacement-model");
            context.runOnClient(client -> {
                chooseContext(client.screen, "На теле");
                field(client.screen, "ID предмета").setValue("eclipseserver:carver_bag");
                press(client.screen, "Найти");
            });
            context.getInput().resizeWindow(960, 480);
            context.waitTicks(5);
            context.takeScreenshot("model-editor-compact-240-height");
            context.runOnClient(client -> {
                require(ModelEditorClient.dirtyCount() > 0, "Resize must preserve the draft");
                client.screen.onClose();
                require(ModelEditorClient.dirtyCount() == 0 && ModelEditorClient.previewPlayer(PREVIEW_ID) == null,
                        "Closing must discard drafts and release detached avatar");
            });
        }
    }

    /** Catches the reported held-tool-to-chest duplicate and lost stack components. */
    private static void verifyEquippedSubject(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.mainHand().set(HumanoidArm.RIGHT);
            client.player.setMainArm(HumanoidArm.RIGHT);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            ItemStack chisel = new ItemStack(CarverItems.CHISEL_POINT);
            chisel.setDamageValue(37);
            client.player.setItemSlot(EquipmentSlot.MAINHAND, chisel);
            client.player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(CarverItems.CHISEL_FLAT));
            client.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(CarverItems.BAG));
            var placement = new ModelPlacement("eclipseserver:carver_chisel_point", ModelPose.FIRST_PERSON_RIGHT_HAND,
                    "", ModelAttachment.BACK, new ModelTransform(1.5f, 0, 0, 0, 0, 0, 1, 1, 1));
            openEditor(new ModelEditorDocument(8, java.util.Map.of(placement.key(), placement)));
            require(contextLabel(client.screen).equals("Контекст: 1-е лицо: правая"),
                    "Opening a held chisel must use the real first-person hand instead of adding a chest copy");
            var preview = ModelEditorClient.previewPlayer(PREVIEW_ID);
            require(equippedCount(preview, CarverItems.CHISEL_POINT) == 1, "Opening must show one existing held chisel");
            require(preview.getItemBySlot(EquipmentSlot.CHEST).is(CarverItems.BAG), "Opening a tool must preserve actual chest equipment");
            require(preview.getMainHandItem().getDamageValue() == 37, "Preview must preserve the held stack components");
            require(field(client.screen, "Сдвиг X").getValue().equals("1.5") && ModelEditorClient.dirtyCount() == 0,
                    "Opening must start at the current server placement without creating a draft");
        });
        context.waitTicks(4);
        context.takeScreenshot("model-editor-existing-chisel-first-person");
        context.runOnClient(client -> {
            chooseContext(client.screen, "3-е лицо: правая");
            chooseContext(client.screen, "На голове");
            var preview = ModelEditorClient.previewPlayer(PREVIEW_ID);
            require(preview.getItemBySlot(EquipmentSlot.HEAD).is(CarverItems.CHISEL_POINT)
                            && preview.getMainHandItem().isEmpty() && equippedCount(preview, CarverItems.CHISEL_POINT) == 1,
                    "Changing context must move the selected instance rather than retain its original hand copy");
            require(preview.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 37, "Moving preview must retain components");
            require(client.player.getMainHandItem().is(CarverItems.CHISEL_POINT)
                            && client.player.getMainHandItem().getDamageValue() == 37
                            && client.player.getOffhandItem().is(CarverItems.CHISEL_FLAT),
                    "Preview relocation must never change the real hands");
            field(client.screen, "ID предмета").setValue("eclipseserver:carver_bag");
            press(client.screen, "Найти");
            require(contextLabel(client.screen).equals("Контекст: На теле")
                            && preview.getItemBySlot(EquipmentSlot.CHEST).is(CarverItems.BAG),
                    "Selecting an actually worn bag must restore its chest context");
            client.screen.onClose();
            client.player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            client.player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(CarverItems.CHISEL_POINT));
            openEditor(ModelEditorDocument.empty());
            require(contextLabel(client.screen).equals("Контекст: 1-е лицо: левая")
                            && field(client.screen, "ID предмета").getValue().equals("eclipseserver:carver_chisel_point"),
                    "An offhand tool must open in its physical left hand when the main hand is empty");
            client.screen.onClose();
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            client.options.mainHand().set(HumanoidArm.LEFT);
            client.player.setMainArm(HumanoidArm.LEFT);
            client.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(CarverItems.CHISEL_POINT));
            client.player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            openEditor(ModelEditorDocument.empty());
            require(contextLabel(client.screen).equals("Контекст: 3-е лицо: левая"),
                    "A left-handed player's main hand must open in the left third-person context");
            client.screen.onClose();
            client.options.mainHand().set(HumanoidArm.RIGHT);
            client.player.setMainArm(HumanoidArm.RIGHT);
            client.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }

    private static void openEditor(ModelEditorDocument document) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "open"); envelope.addProperty("editable", true);
        envelope.addProperty("message", "Редактор готов");
        envelope.add("document", JsonParser.parseString(document.encode()));
        ModelEditorClient.accept(envelope.toString());
    }

    private static String contextLabel(Screen screen) {
        return screen.children().stream().filter(value -> value instanceof Button button
                        && button.getMessage().getString().startsWith("Контекст: "))
                .map(value -> ((Button)value).getMessage().getString()).findFirst().orElseThrow();
    }

    private static int equippedCount(net.minecraft.world.entity.player.Player player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) if (player.getItemBySlot(slot).is(item)) count++;
        return count;
    }

    private static void verifyContexts(net.minecraft.client.Minecraft client) {
        var preview = ModelEditorClient.previewPlayer(PREVIEW_ID);
        var adjustment = new ModelTransform(2, 1, -3, 35, 60, -15, 1.2f, .8f, 1.1f);
        for (ModelPose pose : ModelPose.values()) {
            if (pose == ModelPose.WORN) continue;
            var context = ItemDisplayContext.valueOf(pose.name());
            var original = new ItemStack(MedievalItems.HAMMER);
            var before = new ItemStackRenderState();
            client.getItemModelResolver().updateForLiving(before, original, context, client.player);
            Matrix4f expected = matrix(before, context).mul(new Matrix4f().translate(.5f, .5f, .5f)
                    .translate(adjustment.x()/16, adjustment.y()/16, adjustment.z()/16)
                    .rotateXYZ((float)Math.toRadians(adjustment.rx()), (float)Math.toRadians(adjustment.ry()), (float)Math.toRadians(adjustment.rz()))
                    .scale(adjustment.sx(), adjustment.sy(), adjustment.sz()).translate(-.5f, -.5f, -.5f));
            ModelEditorClient.edit(new ModelPlacement("eclipseserver:ancient_peasant_hammer", pose, "", ModelAttachment.BACK, adjustment));
            var edited = new ItemStackRenderState();
            client.getItemModelResolver().updateForLiving(edited, original, context, preview);
            Matrix4f actual = matrix(edited, context);
            require(actual.equals(expected, 1e-5f), "Runtime must compose the adjustment after the imported display transform: " + pose);
            var live = new ItemStackRenderState();
            client.getItemModelResolver().updateForLiving(live, original, context, client.player);
            require(matrix(live, context).equals(matrix(before, context), 1e-6f), "A preview must not change another entity's render: " + pose);
        }
        ModelEditorClient.edit(new ModelPlacement("eclipseserver:ancient_peasant_hammer", ModelPose.GUI,
                "eclipseserver:carver_chisel_point", ModelAttachment.BACK, ModelTransform.identity()));
        var replacement = new ItemStackRenderState();
        client.getItemModelResolver().updateForLiving(replacement, new ItemStack(MedievalItems.HAMMER), ItemDisplayContext.GUI, preview);
        require(layer(replacement).prepareQuadList().size() == 288, "Replacement must resolve exact point-chisel mesh");
        require(client.player.getMainHandItem().is(MedievalItems.HAMMER), "Model replacement must preserve gameplay item");
    }
    private static Matrix4f matrix(ItemStackRenderState state, ItemDisplayContext context) {
        try {
            var layer = layer(state);
            Field transform = layer.getClass().getDeclaredField("itemTransform"), local = layer.getClass().getDeclaredField("localTransform");
            transform.setAccessible(true); local.setAccessible(true);
            PoseStack stack = new PoseStack();
            ((ItemTransform)transform.get(layer)).apply(context.leftHand(), stack.last());
            return new Matrix4f(stack.last().pose()).mul((Matrix4f)local.get(layer));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static ItemStackRenderState.LayerRenderState layer(ItemStackRenderState state) {
        try {
            Field layers = ItemStackRenderState.class.getDeclaredField("layers"); layers.setAccessible(true);
            return ((ItemStackRenderState.LayerRenderState[])layers.get(state))[0];
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static EditBox field(Screen screen, String label) {
        return screen.children().stream().filter(value -> value instanceof EditBox field && field.getMessage().getString().equals(label))
                .map(value -> (EditBox)value).findFirst().orElseThrow(() -> new AssertionError("Missing field " + label));
    }
    private static void press(Screen screen, String label) {
        Button button = screen.children().stream().filter(value -> value instanceof Button candidate && candidate.getMessage().getString().replace("• ", "").equals(label))
                .map(value -> (Button)value).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
        button.onPress(new KeyEvent(257, 0, 0));
    }
    private static void chooseContext(Screen screen, String label) {
        Button selector = screen.children().stream().filter(value -> value instanceof Button candidate
                && candidate.getMessage().getString().startsWith("Контекст: "))
                .map(value -> (Button)value).findFirst().orElseThrow();
        for (int i = 0; i < ModelPose.values().length; i++) {
            if (selector.getMessage().getString().equals("Контекст: " + label)) return;
            selector.onPress(new KeyEvent(257, 0, 0));
        }
        throw new AssertionError("Context selector failed: " + label);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
