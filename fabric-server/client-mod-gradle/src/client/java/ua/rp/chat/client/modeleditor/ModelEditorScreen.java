package ua.rp.chat.client.modeleditor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.modeleditor.ModelAttachment;
import ua.rp.chat.modeleditor.ModelPlacement;
import ua.rp.chat.modeleditor.ModelPose;
import ua.rp.chat.modeleditor.ModelTransform;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Server-authorized presentation editor. Its player and inventory never enter the live level. */
public final class ModelEditorScreen extends Screen {
    private static final String[] POSES = {"На теле", "1-е лицо: правая", "1-е лицо: левая",
            "3-е лицо: правая", "3-е лицо: левая", "Инвентарь", "На земле", "В рамке", "На голове"};
    private static final String[] ATTACHMENTS = {"Спина", "Пояс", "Правая рука", "Левая рука"};
    private static final String[] ANIMATIONS = {"idle", "walk", "crouch", "carve"};
    private static final String[] ANIMATION_LABELS = {"Покой", "Ходьба", "Присесть", "Резьба"};
    private static final String[] AXES = {"X", "Y", "Z"};
    private final String[] numbers = new String[9];
    private final EditBox[] fields = new EditBox[9];
    private final List<Button> editButtons = new ArrayList<>();
    private final List<Button> animationButtons = new ArrayList<>();
    private String item = "minecraft:stone";
    private ModelPose context = ModelPose.WORN;
    private String targetText = item;
    private String modelText = "";
    private String feedback = "";
    private boolean updating;
    private boolean previewStarted;
    private boolean playerView = true;
    private boolean orbiting;
    private float yaw = 160;
    private float pitch = -10;
    private float itemYaw;
    private float itemPitch;
    private float zoom = 1;
    private int leftWidth;
    private int previewX;
    private int previewY;
    private int previewRight;
    private int previewBottom;
    private int fieldY;
    private int rowGap;
    private EditBox targetField;
    private EditBox modelField;
    private Button poseButton;
    private Button attachmentButton;
    private Button undoButton;
    private Button redoButton;
    private Button saveButton;
    private Button viewButton;
    private ModelPose viewTooltipContext;
    private RemotePlayer previewPlayer;
    private Display.ItemDisplay previewItem;
    private int focusToRestore = -1;
    private int cursorToRestore;
    private long displayedRevision = -1;
    private List<String> itemIds = List.of();

    public ModelEditorScreen() {
        super(Component.literal("Редактор моделей"));
    }

    @Override
    protected void init() {
        if (!previewStarted) {
            ModelEditorClient.beginPreview();
            previewStarted = true;
            itemIds = BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).sorted().toList();
            EquipmentSlot equipped = initialEquipmentSlot();
            if (equipped != null) {
                item = BuiltInRegistries.ITEM.getKey(minecraft.player.getItemBySlot(equipped).getItem()).toString();
                context = equipmentContext(equipped);
            } else if (BuiltInRegistries.ITEM.containsKey(Identifier.parse("eclipseserver:carver_bag"))) {
                item = "eclipseserver:carver_bag";
            }
            playerView = isPlayerContext();
            targetText = item;
            createPreviewPlayer();
            loadPlacement();
        }
        editButtons.clear();
        animationButtons.clear();
        viewTooltipContext = null;
        leftWidth = Math.min(310, Math.max(206, (width - 28) / 2));
        previewX = leftWidth + 20;
        previewRight = width - 8;
        previewY = 27;
        previewBottom = Math.max(100, height - 101);
        boolean compact = height < 270;
        rowGap = compact ? 18 : 23;
        fieldY = compact ? 115 : 130;
        int targetY = compact ? 23 : 27;
        int modelY = compact ? 43 : 49;
        int contextY = compact ? 63 : 72;
        int attachmentY = compact ? 83 : 94;
        targetField = textField(49, targetY, leftWidth - 91, targetText, "ID предмета");
        targetField.setResponder(value -> targetText = value);
        targetField.setTooltip(Tooltip.create(Component.literal("ID или часть ID. Enter — выбрать; Tab — дополнить.")));
        button(leftWidth - 36, targetY, 44, "Найти", this::selectTarget);
        modelField = textField(49, modelY, leftWidth - 41, modelText, "ID модели (пусто — исходная)");
        modelField.setResponder(value -> {
            modelText = value;
            applyModel();
        });
        modelField.setHint(Component.literal("Исходная модель"));
        modelField.setTooltip(Tooltip.create(Component.literal("ID зарегистрированного предмета для замены модели; пусто — исходная. Tab — дополнить.")));
        poseButton = button(8, contextY, leftWidth, "Контекст: " + POSES[context.ordinal()], () -> {
            context = ModelPose.values()[(context.ordinal() + 1) % ModelPose.values().length];
            playerView = isPlayerContext();
            feedback = "";
            loadPlacement();
            refreshWidgets();
            equipPreview();
        });
        poseButton.setTooltip(Tooltip.create(Component.literal("Переключить контекст; Shift+щелчок — предыдущий")));
        attachmentButton = editableButton(8, attachmentY, leftWidth, attachmentLabel(), () -> {
            ModelPlacement old = placement();
            ModelAttachment next = ModelAttachment.values()[(old.attachment().ordinal() + 1) % ModelAttachment.values().length];
            ModelEditorClient.edit(new ModelPlacement(item, context, old.model(), next, old.transform()));
            attachmentButton.setMessage(Component.literal(attachmentLabel()));
        });
        int axisWidth = (leftWidth - 31) / 3;
        for (int i = 0; i < 9; i++) {
            int index = i;
            int x = 39 + (i % 3) * axisWidth;
            int y = fieldY + (i / 3) * rowGap;
            fields[i] = textField(x + 13, y, axisWidth - 27, numbers[i], numberLabel(i));
            fields[i].setMaxLength(20);
            fields[i].setResponder(value -> {
                numbers[index] = value;
                if (!updating) applyNumbers();
            });
            fields[i].setTooltip(Tooltip.create(Component.literal(numberLabel(i) + ". " + (i < 3 ? "−64…64 пикс." : i < 6 ? "−360…360°" : "0.05…4") + "; Shift ± — шаг ×10")));
            editableButton(x, y, 12, "−", () -> step(index, -1));
            editableButton(x + axisWidth - 13, y, 12, "+", () -> step(index, 1));
        }
        int controlsY = fieldY + rowGap * 3 + 1;
        undoButton = editableButton(8, controlsY, 61, "Отмена ↶", () -> {
            ModelEditorClient.undo();
            reloadFields();
        });
        redoButton = editableButton(72, controlsY, 61, "Повтор ↷", () -> {
            ModelEditorClient.redo();
            reloadFields();
        });
        editableButton(136, controlsY, leftWidth - 128, "Сброс", () -> {
            ModelEditorClient.reset(item, context);
            reloadFields();
        }).setTooltip(Tooltip.create(Component.literal("Заводские настройки выбранного предмета и контекста. Применятся после сохранения.")));
        int previewWidth = Math.max(80, previewRight - previewX);
        viewButton = button(previewX, previewBottom + 4, previewWidth, viewLabel(), () -> {
            if (context == ModelPose.WORN) return;
            playerView = !playerView;
            viewButton.setMessage(Component.literal(viewLabel()));
        });
        int animationWidth = (previewWidth - 3) / 2;
        for (int i = 0; i < ANIMATIONS.length; i++) {
            int index = i;
            animationButtons.add(button(previewX + (i % 2) * (animationWidth + 3), previewBottom + 25 + (i / 2) * 21,
                    animationWidth, ANIMATION_LABELS[i], () -> {
                        ModelEditorClient.setPreviewAnimation(ANIMATIONS[index]);
                        playerView = true;
                        viewButton.setMessage(Component.literal(viewLabel()));
                    }));
        }
        int bottom = height - 24;
        int footerWidth = (width - 22) / 3;
        saveButton = editableButton(8, bottom, footerWidth, "Сохранить всем", this::save);
        button(11 + footerWidth, bottom, footerWidth, "Отбросить", () -> {
            ModelEditorClient.discard();
            feedback = "Черновик отменён";
            reloadFields();
        });
        button(14 + footerWidth * 2, bottom, footerWidth, "Обновить с сервера", ModelEditorClient::reload)
                .setTooltip(Tooltip.create(Component.literal("Запросить свежую ревизию. Локальный черновик сохраняется.")));
        restoreFocus();
        updateEnabled();
    }

    private void createPreviewPlayer() {
        if (minecraft.level == null || minecraft.player == null) return;
        previewPlayer = new RemotePlayer(minecraft.level, minecraft.player.getGameProfile()) {
            @Override public PlayerSkin getSkin() {
                return minecraft.player == null ? super.getSkin() : minecraft.player.getSkin();
            }
            @Override public HumanoidArm getMainArm() {
                return minecraft.player == null ? super.getMainArm() : minecraft.player.getMainArm();
            }
            @Override public boolean isModelPartShown(PlayerModelPart part) {
                return minecraft.player == null ? super.isModelPartShown(part) : minecraft.player.isModelPartShown(part);
            }
        };
        // Do not add this entity to ClientLevel, tick its AI, or touch the real inventory.
        previewPlayer.setId(-2_000_000_026);
        previewPlayer.setSilent(true);
        previewPlayer.setNoGravity(true);
        previewPlayer.setPos(minecraft.player.position());
        previewItem = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, minecraft.level);
        previewItem.setId(-2_000_000_027);
        previewItem.setSilent(true);
        previewItem.setNoGravity(true);
        previewItem.setPos(minecraft.player.position());
        ModelEditorClient.setPreviewPlayer(previewPlayer);
        equipPreview();
    }

    private void equipPreview() {
        if (previewPlayer == null || minecraft.player == null) return;
        for (int slot = 0; slot < minecraft.player.getInventory().getContainerSize(); slot++) {
            previewPlayer.getInventory().setItem(slot, minecraft.player.getInventory().getItem(slot).copy());
        }
        previewPlayer.getInventory().setSelectedSlot(minecraft.player.getInventory().getSelectedSlot());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            previewPlayer.setItemSlot(slot, minecraft.player.getItemBySlot(slot).copy());
        }
        ItemStack target = targetStack();
        if (previewItem != null) {
            previewItem.getSlot(0).set(target.copy());
            // Display.tick only updates its local render/interpolation state; no AI or level registration.
            previewItem.tick();
        }
        EquipmentSlot slot = previewEquipmentSlot();
        EquipmentSlot source = equippedSourceSlot(item, slot);
        // This is one selected instance moved between presentation contexts, not an extra item.
        if (source != null && source != slot) previewPlayer.setItemSlot(source, ItemStack.EMPTY);
        previewPlayer.setItemSlot(slot, target);
    }

    private EquipmentSlot previewEquipmentSlot() {
        HumanoidArm mainArm = minecraft.player == null ? HumanoidArm.RIGHT : minecraft.player.getMainArm();
        return switch (context) {
            case WORN -> EquipmentSlot.CHEST;
            case HEAD -> EquipmentSlot.HEAD;
            case FIRST_PERSON_LEFT_HAND, THIRD_PERSON_LEFT_HAND -> mainArm == HumanoidArm.LEFT ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            case FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_RIGHT_HAND -> mainArm == HumanoidArm.RIGHT ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            default -> EquipmentSlot.MAINHAND;
        };
    }

    private EquipmentSlot initialEquipmentSlot() {
        if (minecraft.player == null) return null;
        for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
            if (!minecraft.player.getItemBySlot(slot).isEmpty()) return slot;
        }
        return null;
    }

    private EquipmentSlot equippedSourceSlot(String id, EquipmentSlot preferred) {
        if (minecraft.player == null) return null;
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return null;
        var targetItem = BuiltInRegistries.ITEM.getValue(identifier);
        if (preferred != null && minecraft.player.getItemBySlot(preferred).is(targetItem)) return preferred;
        for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
            if (minecraft.player.getItemBySlot(slot).is(targetItem)) return slot;
        }
        return null;
    }

    private ModelPose equipmentContext(EquipmentSlot slot) {
        if (slot == EquipmentSlot.HEAD) return ModelPose.HEAD;
        if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return ModelPose.WORN;
        boolean left = (minecraft.player.getMainArm() == HumanoidArm.LEFT) == (slot == EquipmentSlot.MAINHAND);
        if (minecraft.options.getCameraType().isFirstPerson())
            return left ? ModelPose.FIRST_PERSON_LEFT_HAND : ModelPose.FIRST_PERSON_RIGHT_HAND;
        return left ? ModelPose.THIRD_PERSON_LEFT_HAND : ModelPose.THIRD_PERSON_RIGHT_HAND;
    }

    private EditBox textField(int x, int y, int w, String value, String label) {
        EditBox field = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal(label)));
        field.setMaxLength(160);
        field.setValue(value == null ? "" : value);
        return field;
    }

    private Button button(int x, int y, int w, String label, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), button -> action.run()).bounds(x, y, Math.max(12, w), 18).build());
    }

    private Button editableButton(int x, int y, int w, String label, Runnable action) {
        Button button = button(x, y, w, label, action);
        editButtons.add(button);
        return button;
    }

    private ModelPlacement placement() {
        return ModelEditorClient.placement(item, context);
    }

    private void loadPlacement() {
        ModelPlacement placement = placement();
        displayedRevision = ModelEditorClient.revision();
        modelText = placement.model();
        ModelTransform t = placement.transform();
        float[] values = {t.x(), t.y(), t.z(), t.rx(), t.ry(), t.rz(), t.sx(), t.sy(), t.sz()};
        for (int i = 0; i < values.length; i++) numbers[i] = format(values[i]);
    }

    private void reloadFields() {
        feedback = "";
        loadPlacement();
        refreshWidgets();
        equipPreview();
    }

    private void refreshWidgets() {
        updating = true;
        try {
            if (modelField != null) modelField.setValue(modelText);
            for (int i = 0; i < fields.length; i++) if (fields[i] != null) fields[i].setValue(numbers[i]);
            if (poseButton != null) poseButton.setMessage(Component.literal("Контекст: " + POSES[context.ordinal()]));
            if (attachmentButton != null) attachmentButton.setMessage(Component.literal(attachmentLabel()));
            if (viewButton != null) viewButton.setMessage(Component.literal(viewLabel()));
        } finally {
            updating = false;
        }
    }

    private String attachmentLabel() {
        return "Крепление: " + ATTACHMENTS[placement().attachment().ordinal()];
    }

    private String viewLabel() {
        return "Вид: " + (playerView ? "игрок" : "предмет") + " ↔";
    }

    private boolean isPlayerContext() {
        return context == ModelPose.WORN || context == ModelPose.HEAD || context == ModelPose.THIRD_PERSON_LEFT_HAND || context == ModelPose.THIRD_PERSON_RIGHT_HAND;
    }

    private void selectTarget() {
        String selection = resolveItem(targetText);
        if (selection == null) {
            feedback = "Предмет не найден: " + targetText;
            return;
        }
        if (!selection.equals(item)) {
            EquipmentSlot source = equippedSourceSlot(selection, null);
            if (source != null) {
                context = equipmentContext(source);
                playerView = isPlayerContext();
            }
        }
        item = selection;
        targetText = item;
        targetField.setValue(item);
        targetField.moveCursorToStart(false);
        reloadFields();
    }

    private String resolveItem(String text) {
        String query = text.trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(query);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) return id.toString();
        if (query.isBlank()) return null;
        return itemIds.stream().filter(value -> value.contains(query)).findFirst().orElse(null);
    }

    private boolean validModel() {
        if (modelText.isBlank()) return true;
        Identifier id = Identifier.tryParse(modelText.trim());
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    private void applyModel() {
        if (updating || !ModelEditorClient.editable()) return;
        if (!validModel()) {
            feedback = "Модель: введите существующий ID предмета";
            return;
        }
        ModelPlacement old = placement();
        String model = modelText.isBlank() ? "" : Identifier.parse(modelText.trim()).toString();
        ModelEditorClient.edit(new ModelPlacement(item, context, model, old.attachment(), old.transform()));
        feedback = "";
    }

    private ModelTransform enteredTransform() {
        float[] values = new float[9];
        for (int i = 0; i < values.length; i++) values[i] = Float.parseFloat(numbers[i].trim().replace(',', '.'));
        return new ModelTransform(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8]);
    }

    private void applyNumbers() {
        if (!ModelEditorClient.editable()) return;
        try {
            ModelTransform transform = enteredTransform();
            ModelPlacement old = placement();
            ModelEditorClient.edit(new ModelPlacement(item, context, old.model(), old.attachment(), transform));
            feedback = validModel() ? "" : "Модель: введите существующий ID предмета";
        } catch (IllegalArgumentException ex) {
            feedback = "Допустимо: позиция ±64; угол ±360°; масштаб 0.05…4";
        }
    }

    private void step(int index, int direction) {
        float value;
        try {
            value = Float.parseFloat(numbers[index].trim().replace(',', '.'));
            if (!Float.isFinite(value)) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            ModelTransform t = placement().transform();
            value = new float[]{t.x(), t.y(), t.z(), t.rx(), t.ry(), t.rz(), t.sx(), t.sy(), t.sz()}[index];
        }
        float step = index < 3 ? 0.25f : index < 6 ? 5 : 0.05f;
        if (minecraft.hasShiftDown()) step *= 10;
        float min = index < 3 ? -64 : index < 6 ? -360 : 0.05f;
        float max = index < 3 ? 64 : index < 6 ? 360 : 4;
        float stepped = new BigDecimal(Float.toString(value)).add(new BigDecimal(Float.toString(direction * step))).floatValue();
        fields[index].setValue(format(Math.clamp(stepped, min, max)));
    }

    private void save() {
        try {
            enteredTransform();
            if (!validModel()) {
                feedback = "Сохранение: неизвестный ID модели";
                return;
            }
            applyModel();
            applyNumbers();
            ModelEditorClient.save();
            feedback = "";
        } catch (IllegalArgumentException ex) {
            feedback = "Исправьте числа перед сохранением";
        }
    }

    private void updateEnabled() {
        boolean editable = ModelEditorClient.editable();
        if (viewButton != null) {
            viewButton.active = context != ModelPose.WORN;
            if (viewTooltipContext != context) {
                viewButton.setTooltip(Tooltip.create(Component.literal(context == ModelPose.WORN
                        ? "Крепление на теле видно только на игроке; отдельный предмет не показывает крепление."
                        : "Переключить между игроком и предметом в выбранном контексте.")));
                viewTooltipContext = context;
            }
        }
        for (int i = 0; i < animationButtons.size(); i++) {
            animationButtons.get(i).setMessage(Component.literal((ANIMATIONS[i].equals(ModelEditorClient.previewAnimation()) ? "• " : "") + ANIMATION_LABELS[i]));
        }
        for (Button button : editButtons) button.active = editable;
        if (undoButton != null) undoButton.active = editable && ModelEditorClient.canUndo();
        if (redoButton != null) redoButton.active = editable && ModelEditorClient.canRedo();
        if (saveButton != null) saveButton.active = editable && ModelEditorClient.dirtyCount() > 0 && !ModelEditorClient.saving();
        if (attachmentButton != null) attachmentButton.active = editable && context == ModelPose.WORN;
        if (modelField != null) {
            modelField.setEditable(editable);
            modelField.setTextColor(validModel() ? 0xffe0e0e0 : 0xffff8585);
        }
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null) continue;
            fields[i].setEditable(editable);
            fields[i].setTextColor(validNumber(i) ? 0xffe0e0e0 : 0xffff8585);
        }
    }

    private boolean validNumber(int index) {
        try {
            float value = Float.parseFloat(numbers[index].trim().replace(',', '.'));
            return Float.isFinite(value) && (index < 3 ? Math.abs(value) <= 64 : index < 6 ? Math.abs(value) <= 360 : value >= 0.05f && value <= 4);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    @Override
    public void tick() {
        updateEnabled();
        if (displayedRevision != ModelEditorClient.revision() && validModel()) {
            boolean valid = true;
            for (int i = 0; i < numbers.length; i++) valid &= validNumber(i);
            if (valid) {
                loadPlacement();
                refreshWidgets();
            }
        }
        if (previewPlayer == null) return;
        previewPlayer.tickCount++;
        String animation = ModelEditorClient.previewAnimation();
        boolean crouch = animation.equals("crouch");
        previewPlayer.setShiftKeyDown(crouch);
        previewPlayer.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
        previewPlayer.walkAnimation.update(animation.equals("walk") ? 0.8f : 0, 0.4f, 1);
        previewPlayer.oAttackAnim = previewPlayer.attackAnim;
        previewPlayer.attackAnim = animation.equals("carve") ? (previewPlayer.tickCount % 16) / 16f : 0;
        previewPlayer.yBodyRot = previewPlayer.yBodyRotO = 180;
        previewPlayer.yHeadRot = previewPlayer.yHeadRotO = 180;
        previewPlayer.setYRot(180);
        previewPlayer.setXRot(0);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xed10151d);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        graphics.text(font, "Редактор моделей", 8, 7, 0xfff0d7a0);
        graphics.text(font, "ID", 8, targetField.getY() + 5, 0xffb2bdc9);
        graphics.text(font, "Модель", 8, modelField.getY() + 5, 0xffb2bdc9);
        int axisWidth = (leftWidth - 31) / 3;
        for (int i = 0; i < 3; i++) {
            graphics.centeredText(font, AXES[i], 39 + i * axisWidth + axisWidth / 2, fieldY - 11, 0xffb2bdc9);
        }
        graphics.text(font, "Сдвиг", 8, fieldY + 5, 0xffb2bdc9);
        graphics.text(font, "Угол", 8, fieldY + rowGap + 5, 0xffb2bdc9);
        graphics.text(font, "Размер", 8, fieldY + rowGap * 2 + 5, 0xffb2bdc9);
        graphics.fill(previewX, previewY, previewRight, previewBottom, 0xff202936);
        graphics.outline(previewX, previewY, previewRight - previewX, previewBottom - previewY, 0xff435266);
        extractPreview(graphics, delta);
        graphics.text(font, "Тянуть — обзор; колесо — масштаб", previewX + 4, previewY + 4, 0xffaeb9c7);
        graphics.text(font, "Черновик: " + ModelEditorClient.dirtyCount() + " • рев. " + ModelEditorClient.revision(),
                previewX, 8, 0xffb2bdc9);
        String status = feedback.isEmpty() ? ModelEditorClient.status() : feedback;
        graphics.enableScissor(8, height - 49, 8 + leftWidth, height - 27);
        graphics.textWithWordWrap(font, Component.literal(status == null ? "" : status), 8, height - 49, leftWidth,
                feedback.isEmpty() ? 0xffc4d0db : 0xffffbd83, false);
        graphics.disableScissor();
        if (mouseX >= 8 && mouseX < 8 + leftWidth && mouseY >= height - 49 && mouseY < height - 27) {
            graphics.setTooltipForNextFrame(Component.literal(status == null ? "" : status), mouseX, mouseY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (targetField.isFocused() || modelField.isFocused()) {
            String query = (targetField.isFocused() ? targetText : modelText).trim().toLowerCase(Locale.ROOT);
            if (!query.isBlank()) {
                List<String> matches = itemIds.stream().filter(id -> id.contains(query)).limit(4).toList();
                if (!matches.isEmpty()) graphics.setComponentTooltipForNextFrame(font, matches.stream().map(Component::literal).map(c -> (Component)c).toList(), mouseX, mouseY);
            }
        }
    }

    private void extractPreview(GuiGraphicsExtractor graphics, float delta) {
        if (previewPlayer == null) return;
        if (playerView) {
            EntityRenderState state = minecraft.getEntityRenderDispatcher().extractEntity(previewPlayer, delta);
            if (state instanceof LivingEntityRenderState living) {
                living.bodyRot = 180;
                living.yRot = 0;
                living.xRot = 0;
            }
            float scale = Math.min((previewBottom - previewY - 20) / 2.2f, (previewRight - previewX) / 1.6f) * zoom;
            Quaternionf tilt = new Quaternionf().rotateX((float)Math.toRadians(pitch));
            Quaternionf rotation = new Quaternionf().rotateZ((float)Math.PI).mul(tilt).rotateY((float)Math.toRadians(yaw));
            graphics.entity(state, scale, new Vector3f(0, state.boundingBoxHeight / 2, 0), rotation, tilt,
                    previewX + 1, previewY + 15, previewRight - 1, previewBottom - 1);
        } else {
            if (previewItem == null) return;
            ItemDisplayEntityRenderState state = (ItemDisplayEntityRenderState) minecraft.getEntityRenderDispatcher().extractEntity(previewItem, delta);
            ModelEditorClient.beginItemPreview(context);
            try {
                // Resolve real geometry in the selected native context, including the local replacement/transform.
                // A GUI atlas icon would be rasterized at 16px before scaling and lose model texture detail.
                minecraft.getItemModelResolver().updateForLiving(state.item, targetStack(),
                        ModelEditorClient.itemPreviewContext(), previewPlayer);
            } finally {
                ModelEditorClient.endItemPreview();
            }
            float scale = Math.min(previewRight - previewX - 24, previewBottom - previewY - 28) * 0.65f * zoom;
            Quaternionf tilt = new Quaternionf().rotateX((float)Math.toRadians(itemPitch));
            // ItemDisplayRenderer rotates Y by 180 degrees. Cancel that so an untouched camera
            // shows the exact native display orientation; user orbit remains independent of model edits.
            Quaternionf rotation = new Quaternionf().rotateZ((float)Math.PI).mul(tilt)
                    .rotateY((float)Math.toRadians(itemYaw)).rotateY((float)Math.PI);
            graphics.entity(state, scale, new Vector3f(), rotation, tilt,
                    previewX + 1, previewY + 15, previewRight - 1, previewBottom - 1);
        }
    }

    private ItemStack targetStack() {
        Identifier id = Identifier.tryParse(item);
        if (id == null) return ItemStack.EMPTY;
        EquipmentSlot source = equippedSourceSlot(item, previewEquipmentSlot());
        if (source != null) return minecraft.player.getItemBySlot(source).copy();
        var targetItem = BuiltInRegistries.ITEM.getValue(id);
        if (minecraft.player != null) {
            for (int slot = 0; slot < minecraft.player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = minecraft.player.getInventory().getItem(slot);
                if (stack.is(targetItem)) return stack.copy();
            }
        }
        return new ItemStack(targetItem);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (insidePreview(event.x(), event.y())) {
            clearFocus();
            orbiting = true;
            return true;
        }
        if (event.hasShiftDown() && poseButton.isMouseOver(event.x(), event.y())) {
            context = ModelPose.values()[(context.ordinal() + ModelPose.values().length - 1) % ModelPose.values().length];
            playerView = isPlayerContext();
            reloadFields();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (orbiting) {
            if (playerView) {
                yaw += (float)dragX * 1.5f;
                pitch = Math.clamp(pitch + (float)dragY, -75, 75);
            } else {
                itemYaw += (float)dragX * 1.5f;
                itemPitch = Math.clamp(itemPitch + (float)dragY, -75, 75);
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (orbiting) {
            orbiting = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (insidePreview(mouseX, mouseY)) {
            zoom = Math.clamp(zoom * (float)Math.pow(1.12, vertical), 0.35f, 2.5f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private boolean insidePreview(double x, double y) {
        return x >= previewX && x < previewRight && y >= previewY && y < previewBottom;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == 83) {
            save();
            return true;
        }
        if (event.key() == 257 || event.key() == 335) {
            if (targetField.isFocused()) {
                selectTarget();
                return true;
            }
            if (modelField.isFocused()) {
                String model = modelText.isBlank() ? "" : resolveItem(modelText);
                if (model != null) modelField.setValue(model);
                else feedback = "Неизвестный ID модели";
                return true;
            }
        }
        if (event.key() == 258 && !event.hasShiftDown() && (targetField.isFocused() || modelField.isFocused())) {
            EditBox field = targetField.isFocused() ? targetField : modelField;
            String match = resolveItem(field.getValue());
            if (match != null && !match.equals(field.getValue())) {
                field.setValue(match);
                field.moveCursorToEnd(false);
                return true;
            }
        }
        if (event.hasControlDown() && !(getFocused() instanceof EditBox)) {
            if (event.key() == 90) {
                if (event.hasShiftDown()) ModelEditorClient.redo(); else ModelEditorClient.undo();
                reloadFields();
                return true;
            }
            if (event.key() == 89) {
                ModelEditorClient.redo();
                reloadFields();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void resize(int width, int height) {
        GuiEventListener focus = getFocused();
        focusToRestore = focus == targetField ? 9 : focus == modelField ? 10 : -1;
        for (int i = 0; i < fields.length; i++) if (focus == fields[i]) focusToRestore = i;
        if (focus instanceof EditBox field) cursorToRestore = field.getCursorPosition();
        super.resize(width, height);
    }

    private void restoreFocus() {
        EditBox focus = focusToRestore == 9 ? targetField : focusToRestore == 10 ? modelField : focusToRestore >= 0 ? fields[focusToRestore] : null;
        if (focus != null) {
            setInitialFocus(focus);
            focus.setCursorPosition(Math.min(cursorToRestore, focus.getValue().length()));
        }
        focusToRestore = -1;
    }

    @Override
    protected void setInitialFocus() {
        // Screen calls this again after rebuildWidgets; retain the restored field and cursor.
        if (getFocused() == null) super.setInitialFocus();
    }

    @Override
    public void removed() {
        ModelEditorClient.setPreviewPlayer(null);
        ModelEditorClient.endItemPreview();
        ModelEditorClient.endPreview();
        previewPlayer = null;
        previewItem = null;
        previewStarted = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String format(float value) {
        return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    private static String numberLabel(int index) {
        return (index < 3 ? "Сдвиг " : index < 6 ? "Поворот " : "Масштаб ") + AXES[index % 3];
    }
}
