package ua.rp.chat.client.carry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.carry.CarryActionPayload;
import ua.rp.chat.carry.CarrySyncPayload;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.carver.CarverClientState;

/** Registers the carry feature independently of the existing animation initializer. */
public final class CarryClient implements ClientModInitializer {
    private static KeyMapping menu;
    private static KeyMapping rotateLeft, rotateRight;
    private static long generation;
    private static Object lastLevel;
    private static boolean requested;
    private static boolean placing;
    private static int yaw;
    private static CarryTargeting.Target focus;
    private static String lastName = "";
    public static final CarryFeedback FEEDBACK = new CarryFeedback();

    @Override public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(CarryActionPayload.TYPE, CarryActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CarrySyncPayload.TYPE, CarrySyncPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CarrySyncPayload.TYPE, (payload, context) -> {
            long captured = generation;
            context.client().execute(() -> { if (captured == generation) CarryClientState.accept(payload.json()); });
        });
        menu = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.eclipse.carry_menu", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V, KeyMapping.Category.GAMEPLAY));
        rotateLeft = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.eclipse.carry_rotate_left", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT, KeyMapping.Category.GAMEPLAY));
        rotateRight = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.eclipse.carry_rotate_right", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT, KeyMapping.Category.GAMEPLAY));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { generation++; reset(); });
        ClientTickEvents.END_CLIENT_TICK.register(CarryClient::tick);
        LevelRenderEvents.COLLECT_SUBMITS.register(CarryWorldFeedback::submitPreview);
        LevelRenderEvents.END_MAIN.register(CarryWorldFeedback::renderLines);
    }
    public static double seconds() { return System.nanoTime() / 1_000_000_000.0; }
    public static boolean supported() { return ClientPlayNetworking.canSend(CarryActionPayload.TYPE); }
    public static boolean placing() { return placing && CarryClientState.local() != null; }
    public static int yaw() { return yaw; }
    public static CarryTargeting.Target focus() { return focus; }
    public static String lastName() { return lastName; }

    private static void reset() {
        CarryClientState.clear(); focus = null; lastName = ""; placing = false; requested = false; lastLevel = null;
        CarryWorldFeedback.clear();
    }
    private static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        if (lastLevel != client.level) {
            if (lastLevel != null) CarryClientState.clear();
            lastLevel = client.level; requested = false; placing = false;
            CarryWorldFeedback.clear();
        }
        CarryClientState.tick();
        if (!supported()) { focus = null; return; }
        if (!requested) { action("sync"); requested = true; }
        if (CarryClientState.local() == null) placing = false;
        while (rotateLeft.consumeClick()) if (placing() && client.screen == null) rotate(-1);
        while (rotateRight.consumeClick()) if (placing() && client.screen == null) rotate(1);
        if (client.screen == null && !client.player.isSpectator() && !CarverClientState.working()
                && !SmartCameraManager.getInstance().isCameraFailClosed()) {
            focus = placing || CarryClientState.twoHands() ? null : CarryTargeting.find(client, client.player.isShiftKeyDown());
            if (focus != null) lastName = focus.name();
            while (menu.consumeClick()) client.setScreen(new CarryActionScreen());
        } else { focus = null; while (menu.consumeClick()) {} }
    }
    public static void startPlacement() {
        Minecraft client = Minecraft.getInstance();
        if (CarryClientState.local() == null || client.player == null) return;
        yaw = CarryMath.snapYaw(client.player.getYRot()); placing = true;
        client.setScreen(null);
    }
    public static void rotate(int quarterTurns) { yaw = Math.floorMod(yaw + 90 * quarterTurns, 360); }
    public static void cancelPlacement() { placing = false; }

    /** Called before carving/vanilla RMB handlers; small item use remains native unless targeting a prop. */
    public static boolean handleUse(Minecraft client) {
        if (!supported() || client.player == null || client.level == null || client.screen != null) return false;
        if (SmartCameraManager.getInstance().isCameraFailClosed()) return true;
        if (placing()) {
            JsonObject request = request("place"); request.addProperty("yaw", yaw);
            send(request); FEEDBACK.press(seconds()); return true;
        }
        if (CarryClientState.twoHands()) { client.setScreen(new CarryActionScreen()); return true; }
        if (CarverClientState.working()) return false;
        CarryTargeting.Target target = CarryTargeting.find(client, client.player.isShiftKeyDown());
        if (target == null) return false;
        JsonObject request = request("take");
        if (target.id() != null) request.addProperty("target", target.id().toString());
        else if (target.block() != null) {
            BlockPos p = target.block(); JsonArray coordinates = new JsonArray();
            coordinates.add(p.getX()); coordinates.add(p.getY()); coordinates.add(p.getZ()); request.add("block", coordinates);
        }
        send(request); FEEDBACK.press(seconds()); return true;
    }
    public static boolean suppressAttack() {
        if (placing()) { cancelPlacement(); return true; }
        return supported() && CarryClientState.twoHands();
    }
    public static void action(String action) { send(request(action)); }
    public static void offer() {
        Minecraft client = Minecraft.getInstance();
        var target = CarryTargeting.player(client);
        if (target == null) { message("Наведитесь на игрока рядом, чтобы предложить предмет."); return; }
        JsonObject request = request("offer"); request.addProperty("target", target.getUUID().toString()); send(request);
    }
    public static void respond(boolean accept) {
        var offer = CarryClientState.incoming(); if (offer == null) return;
        JsonObject request = request(accept ? "accept" : "decline"); request.addProperty("offer", offer.id().toString()); send(request);
    }
    private static JsonObject request(String action) { JsonObject request = new JsonObject(); request.addProperty("action", action); return request; }
    private static void send(JsonObject request) {
        if (!supported()) { message("Сервер пока не поддерживает перенос предметов."); return; }
        ClientPlayNetworking.send(new CarryActionPayload(request.toString()));
    }
    public static void message(String text) { Minecraft.getInstance().gui.setOverlayMessage(Component.literal(text), false); }
}
