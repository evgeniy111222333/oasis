package ua.rp.chat.modeleditor;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import ua.rp.chat.RPChat;

/** Minecraft adapter; all changes execute on the server thread and use the same pure authority guard. */
public final class ModelEditorManager {
    private final RPChat plugin;
    private final ModelEditorService service;
    public ModelEditorManager(RPChat plugin) {
        this.plugin = plugin;
        this.service = new ModelEditorService(new ModelEditorStore(
                plugin.getDataFolder().toPath().resolve("model-placements.json")),
                id -> BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)));
        if (!service.loadWarning().isEmpty()) plugin.getLogger().warning(service.loadWarning());
    }
    public void register() {
        PayloadTypeRegistry.serverboundPlay().register(ModelEditorRequestPayload.TYPE, ModelEditorRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModelEditorSyncPayload.TYPE, ModelEditorSyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ModelEditorRequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    // A delayed packet from an old connection must not act for its replacement session.
                    if (context.server().getPlayerList().getPlayer(player.getUUID()) != player) return;
                    handle(player, payload.json());
                }));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("modeleditor")
                        .requires(source -> RPChat.hasPermission(source, "rpchat.admin", 4))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (!ServerPlayNetworking.canSend(player, ModelEditorSyncPayload.TYPE)) {
                                context.getSource().sendFailure(Component.literal("Обновите клиентский мод для редактора моделей."));
                                return 0;
                            }
                            return handle(player, "{\"action\":\"open\"}") ? 1 : 0;
                        })
                        .then(Commands.literal("reload").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            return handle(player, "{\"action\":\"reload\"}") ? 1 : 0;
                        }))));
    }
    public void onJoin(ServerPlayer player) { send(player, "state", "", service.document()); }
    private boolean authenticated(ServerPlayer player) {
        return plugin.getAuthManager() != null && !plugin.getAuthManager().isPendingAuth(player.getUUID());
    }
    private boolean administrator(ServerPlayer player) { return RPChat.hasPermission(player, "rpchat.admin", 4); }
    private boolean handle(ServerPlayer player, String request) {
        ModelEditorService.Result result = service.handle(authenticated(player), administrator(player), request);
        if (result.changed()) {
            for (ServerPlayer other : plugin.getServer().getPlayerList().getPlayers())
                send(other, "state", "", result.document());
        }
        send(player, result.type(), result.message(), result.document());
        if (!result.success()) player.sendSystemMessage(Component.literal(result.message()));
        return result.success();
    }
    private void send(ServerPlayer player, String type, String message, ModelEditorDocument document) {
        if (!ServerPlayNetworking.canSend(player, ModelEditorSyncPayload.TYPE)) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("editable", authenticated(player) && administrator(player));
        envelope.addProperty("message", message);
        envelope.add("document", ModelEditorJson.parse(document.encode(), ModelEditorDocument.MAX_JSON_BYTES));
        ServerPlayNetworking.send(player, new ModelEditorSyncPayload(envelope.toString()));
    }
}
