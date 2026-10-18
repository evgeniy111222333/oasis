package ua.rp.chat.client.modeleditor;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.item.ItemDisplayContext;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.modeleditor.*;

/** Client networking and editor session. Drafts are visible only to detached previews. */
public final class ModelEditorClient {
    private static final ModelEditorDraft DRAFT = new ModelEditorDraft();
    private static boolean editable, saving;
    private static String status = "Ожидание настроек сервера";
    private static RemotePlayer previewPlayer;
    private static String animation = "idle";
    private static double ticks;
    private static ModelPose itemContext;
    private static volatile long connectionGeneration;
    private ModelEditorClient() { }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(ModelEditorRequestPayload.TYPE, ModelEditorRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModelEditorSyncPayload.TYPE, ModelEditorSyncPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ModelEditorSyncPayload.TYPE, (payload, context) -> {
            long generation = connectionGeneration;
            context.client().execute(() -> { if (generation == connectionGeneration) accept(payload.json()); });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> { if (DRAFT.active()) ticks++; });
    }
    public static void accept(String json) {
        try {
            JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
            String type = envelope.get("type").getAsString();
            if (envelope.has("document")) DRAFT.update(ModelEditorDocument.decode(envelope.get("document").toString()));
            if (envelope.has("editable")) editable = envelope.get("editable").getAsBoolean();
            if (envelope.has("message")) status = envelope.get("message").getAsString();
            if (type.equals("error") || type.equals("saved")) { saving = false; DRAFT.rebase(); }
            if (type.equals("open") && editable) Minecraft.getInstance().setScreen(new ModelEditorScreen());
        } catch (RuntimeException invalid) {
            saving = false;
            status = "Сервер прислал некорректные настройки моделей";
            EclipseClientMod.LOGGER.warn("[MODEL-EDITOR] Rejected malformed state", invalid);
        }
    }
    private static void send(JsonObject request) {
        if (!ClientPlayNetworking.canSend(ModelEditorRequestPayload.TYPE)) {
            status = "Сервер не поддерживает редактор моделей"; saving = false; return;
        }
        try { ClientPlayNetworking.send(new ModelEditorRequestPayload(request.toString())); }
        catch (RuntimeException failure) {
            saving = false; status = "Не удалось отправить настройки. Черновик сохранён.";
            EclipseClientMod.LOGGER.warn("[MODEL-EDITOR] Unable to send request", failure);
        }
    }
    public static void requestOpen() { JsonObject request = new JsonObject(); request.addProperty("action", "open"); send(request); }
    public static void reload() { JsonObject request = new JsonObject(); request.addProperty("action", "reload"); send(request); }
    public static void beginPreview() { DRAFT.begin(); ticks = 0; animation = "idle"; }
    public static void endPreview() { DRAFT.end(); previewPlayer = null; itemContext = null; }
    public static ModelPlacement placement(String item, ModelPose pose) { return DRAFT.get(item, pose, true); }
    public static ModelPlacement authoritativePlacement(String item, ModelPose pose) { return DRAFT.get(item, pose, false); }
    public static boolean hasPlacement(String item, ModelPose pose) { return DRAFT.document().entries().containsKey(item + "|" + pose.name()); }
    public static void edit(ModelPlacement value) { DRAFT.edit(value); }
    public static void reset(String item, ModelPose pose) { edit(ModelPlacement.defaults(item, pose)); }
    public static void undo() { DRAFT.undo(); }
    public static void redo() { DRAFT.redo(); }
    public static boolean canUndo() { return DRAFT.canUndo(); }
    public static boolean canRedo() { return DRAFT.canRedo(); }
    public static void discard() { DRAFT.discard(); status = "Несохранённые изменения отменены"; }
    public static int dirtyCount() { return DRAFT.dirtyCount(); }
    public static boolean editable() { return editable; }
    public static boolean saving() { return saving; }
    public static String status() { return status; }
    public static long revision() { return DRAFT.document().revision(); }
    public static void save() {
        if (!editable || saving || DRAFT.dirtyCount() == 0) return;
        try { DRAFT.document().apply(DRAFT.changes()); }
        catch (IllegalArgumentException tooLarge) { status = "Превышен размер настроек. Сократите число изменённых контекстов."; return; }
        JsonObject request = new JsonObject(); request.addProperty("action", "save"); request.addProperty("revision", DRAFT.saveRevision());
        JsonArray entries = new JsonArray();
        DRAFT.changes().forEach(value -> entries.add(value.toJson()));
        request.add("entries", entries); saving = true; status = "Сохраняем настройки для всех игроков…"; send(request);
    }
    public static void setPreviewPlayer(RemotePlayer player) { previewPlayer = player; }
    public static RemotePlayer previewPlayer(int id) { return previewPlayer != null && previewPlayer.getId() == id ? previewPlayer : null; }
    public static boolean isPreviewPlayer(net.minecraft.world.entity.player.Player player) { return previewPlayer == player && player != null; }
    public static void setPreviewAnimation(String value) { animation = value; }
    public static String previewAnimation() { return animation; }
    public static double previewTicks() { return ticks + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false); }
    public static void beginItemPreview(ModelPose pose) { itemContext = pose; }
    public static void endItemPreview() { itemContext = null; }
    public static ItemDisplayContext itemPreviewContext() {
        if (itemContext == null) return null;
        return itemContext == ModelPose.WORN ? ItemDisplayContext.NONE : ItemDisplayContext.valueOf(itemContext.name());
    }
    public static ModelPose previewItemPose() { return itemContext; }
    private static void clear() { connectionGeneration++; DRAFT.clear(); previewPlayer = null; itemContext = null; editable = false; saving = false; ticks = 0; status = "Ожидание настроек сервера"; }
}
