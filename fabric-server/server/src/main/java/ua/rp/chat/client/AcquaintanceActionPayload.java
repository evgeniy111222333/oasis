package ua.rp.chat.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record AcquaintanceActionPayload(int action, UUID targetId, String text) implements CustomPacketPayload {
    public static final Type<AcquaintanceActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "acq_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AcquaintanceActionPayload> CODEC = StreamCodec.ofMember(
            AcquaintanceActionPayload::write,
            AcquaintanceActionPayload::read
    );

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeInt(action);
        buf.writeLong(targetId.getMostSignificantBits());
        buf.writeLong(targetId.getLeastSignificantBits());
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, 32760);
        buf.writeShort(length);
        buf.writeBytes(bytes, 0, length);
    }

    private static AcquaintanceActionPayload read(RegistryFriendlyByteBuf buf) {
        int action = buf.readInt();
        UUID target = new UUID(buf.readLong(), buf.readLong());
        int length = buf.readUnsignedShort();
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new AcquaintanceActionPayload(action, target, new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
