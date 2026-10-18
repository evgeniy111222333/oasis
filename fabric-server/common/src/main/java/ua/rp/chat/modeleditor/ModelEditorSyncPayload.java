package ua.rp.chat.modeleditor;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ModelEditorSyncPayload(String json) implements CustomPacketPayload {
    public static final int MAX_BYTES = 32_000;
    public static final Type<ModelEditorSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "model_editor_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelEditorSyncPayload> CODEC =
            StreamCodec.ofMember(ModelEditorSyncPayload::write, ModelEditorSyncPayload::read);
    public ModelEditorSyncPayload { ModelEditorJson.bounded(json, MAX_BYTES); }
    private void write(RegistryFriendlyByteBuf buffer) { ModelEditorWire.write(buffer, json, MAX_BYTES); }
    private static ModelEditorSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new ModelEditorSyncPayload(ModelEditorWire.read(buffer, MAX_BYTES));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
