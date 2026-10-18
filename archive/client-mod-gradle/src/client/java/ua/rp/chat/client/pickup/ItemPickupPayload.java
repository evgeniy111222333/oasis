package ua.rp.chat.client.pickup;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Client request to pick up one specifically raycast dropped item. */
public record ItemPickupPayload(UUID itemId) implements CustomPacketPayload {
    public static final Type<ItemPickupPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "item_pickup"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemPickupPayload> CODEC = StreamCodec.ofMember(
            ItemPickupPayload::write, ItemPickupPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(itemId.getMostSignificantBits());
        buffer.writeLong(itemId.getLeastSignificantBits());
    }

    private static ItemPickupPayload read(RegistryFriendlyByteBuf buffer) {
        return new ItemPickupPayload(new UUID(buffer.readLong(), buffer.readLong()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
