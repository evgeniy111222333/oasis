package ua.rp.chat.client.stonemason;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server drafting input. Server mirror: this file is duplicated verbatim in
 * the client module; the channel id and field order must stay identical on both sides.
 */
public record StonemasonActionPayload(int protocolVersion, int action,
                                      int x, int y, int z, byte[] data)
        implements CustomPacketPayload {
    public static final Type<StonemasonActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "stonemason_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StonemasonActionPayload> CODEC = StreamCodec.ofMember(
            StonemasonActionPayload::write, StonemasonActionPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(protocolVersion);
        buffer.writeByte(action);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeByteArray(data == null ? new byte[0] : data);
    }

    private static StonemasonActionPayload read(RegistryFriendlyByteBuf buffer) {
        return new StonemasonActionPayload(
                buffer.readVarInt(), buffer.readUnsignedByte(),
                buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
