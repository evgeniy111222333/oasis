package ua.rp.chat.client.heavyhammer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HeavyHammerActionPayload(int x, int y, int z, int cell, int revision, int clientSequence)
        implements CustomPacketPayload {
    public static final Type<HeavyHammerActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "hammer_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HeavyHammerActionPayload> CODEC = StreamCodec.ofMember(
            HeavyHammerActionPayload::write, HeavyHammerActionPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeShort(cell);
        buffer.writeInt(revision);
        buffer.writeInt(clientSequence);
    }

    private static HeavyHammerActionPayload read(RegistryFriendlyByteBuf buffer) {
        return new HeavyHammerActionPayload(buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readUnsignedShort(), buffer.readInt(), buffer.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
