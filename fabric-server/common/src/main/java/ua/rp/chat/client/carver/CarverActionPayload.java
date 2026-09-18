package ua.rp.chat.client.carver;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server drafting input. Lives in the shared {@code common/} source set, so the channel
 * id and field order are compiled into both sides at once.
 *
 * <p>{@code sessionNonce} is the per-login token issued in the microvoxel {@code HELLO_ACK}; the
 * server rejects a replay-sensitive drafting action whose token is not current, so a captured
 * packet cannot be replayed across a relog or a server restart.</p>
 */
public record CarverActionPayload(int protocolVersion, long sessionNonce, int action,
                                      int x, int y, int z, byte[] data)
        implements CustomPacketPayload {
    public static final Type<CarverActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "carver_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CarverActionPayload> CODEC = StreamCodec.ofMember(
            CarverActionPayload::write, CarverActionPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(protocolVersion);
        buffer.writeLong(sessionNonce);
        buffer.writeByte(action);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeByteArray(data == null ? new byte[0] : data);
    }

    private static CarverActionPayload read(RegistryFriendlyByteBuf buffer) {
        return new CarverActionPayload(
                buffer.readVarInt(), buffer.readLong(), buffer.readUnsignedByte(),
                buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
