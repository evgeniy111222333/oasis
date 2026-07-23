package ua.rp.chat.client.heavyhammer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ua.rp.chat.client.EclipseClientMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Клиентский виртуальный слот портупеи: предмет остаётся в инвентаре, а слот хранит состояние ношения. */
public final class HammerHolsterClientState {
    public static final String MODEL_ID = "eclipseclient:heavy_hammer_holster";
    public static final String DISPLAY_NAME = "Кожаная портупея тяжёлого молота";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir()
            .resolve("eclipse-hammer-holster.json");
    private static UUID loadedPlayer;
    private static boolean equipped;
    private static int missingTicks;

    private HammerHolsterClientState() {
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            missingTicks = 0;
            return;
        }
        UUID playerId = client.player.getUUID();
        if (!playerId.equals(loadedPlayer)) load(playerId);
        if (!equipped || hasHolster(client.player)) {
            missingTicks = 0;
            return;
        }
        // После входа сервер может прислать инвентарь на несколько тиков позже состояния игрока.
        if (++missingTicks > 40) setEquipped(client, false, false);
    }

    public static boolean handleUse(Minecraft client) {
        if (client == null || client.player == null || client.screen != null) return false;
        ItemStack main = client.player.getMainHandItem();
        ItemStack offhand = client.player.getOffhandItem();
        if (!isHolster(main) && !isHolster(offhand)) return false;
        setEquipped(client, !isEquipped(client.player), true);
        return true;
    }

    public static boolean isEquipped(Player player) {
        Minecraft client = Minecraft.getInstance();
        return equipped && player != null && client.player == player;
    }

    public static void setEquipped(Minecraft client, boolean value, boolean notify) {
        if (client == null || client.player == null) return;
        if (value && !hasHolster(client.player)) {
            if (notify) client.gui.setOverlayMessage(Component.literal(
                    "Портупея должна находиться в инвентаре."), false);
            return;
        }
        if (!client.player.getUUID().equals(loadedPlayer)) load(client.player.getUUID());
        equipped = value;
        missingTicks = 0;
        save(client.player.getUUID());
        if (notify) {
            client.gui.setOverlayMessage(Component.literal(value
                    ? "Вы надели портупею тяжёлого молота."
                    : "Вы сняли портупею тяжёлого молота."), false);
        }
    }

    public static boolean hasHolster(Player player) {
        return !findHolster(player).isEmpty();
    }

    public static ItemStack findHolster(Player player) {
        if (player == null) return ItemStack.EMPTY;
        ItemStack main = player.getMainHandItem();
        if (isHolster(main)) return main;
        ItemStack offhand = player.getOffhandItem();
        if (isHolster(offhand)) return offhand;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isHolster(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static boolean isHolster(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        return model != null && MODEL_ID.equals(model.toString());
    }

    public static ItemStack displayStack(Player player) {
        ItemStack owned = findHolster(player);
        return owned.isEmpty() ? createStack() : owned.copyWithCount(1);
    }

    /** Стек-представление; вызывается только после завершения bootstrap реестров Minecraft. */
    public static ItemStack createStack() {
        ItemStack stack = new ItemStack(Items.LEATHER);
        stack.set(DataComponents.ITEM_MODEL, Identifier.parse(MODEL_ID));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(DISPLAY_NAME));
        return stack;
    }

    private static void load(UUID playerId) {
        loadedPlayer = playerId;
        equipped = false;
        missingTicks = 0;
        if (!Files.isRegularFile(CONFIG)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            equipped = root.has(playerId.toString()) && root.get(playerId.toString()).getAsBoolean();
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("Не удалось прочитать состояние портупеи молота.", error);
        }
    }

    private static void save(UUID playerId) {
        try {
            JsonObject root = Files.isRegularFile(CONFIG)
                    ? JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            root.addProperty(playerId.toString(), equipped);
            Files.createDirectories(CONFIG.getParent());
            Path temporary = CONFIG.resolveSibling(CONFIG.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("Не удалось сохранить состояние портупеи молота.", error);
        }
    }
}
