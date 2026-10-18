package ua.rp.chat.modeleditor;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ModelEditorRequestPayload(String json) implements CustomPacketPayload {
    public static final int MAX_BYTES = 30_000;
    public static final Type<ModelEditorRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "model_editor_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelEditorRequestPayload> CODEC =
            StreamCodec.ofMember(ModelEditorRequestPayload::write, ModelEditorRequestPayload::read);
    public ModelEditorRequestPayload { ModelEditorJson.bounded(json, MAX_BYTES); }
    private void write(RegistryFriendlyByteBuf buffer) { ModelEditorWire.write(buffer, json, MAX_BYTES); }
    private static ModelEditorRequestPayload read(RegistryFriendlyByteBuf buffer) {
        return new ModelEditorRequestPayload(ModelEditorWire.read(buffer, MAX_BYTES));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
