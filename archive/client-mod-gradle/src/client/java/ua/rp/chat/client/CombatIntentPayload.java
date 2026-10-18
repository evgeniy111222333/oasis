package ua.rp.chat.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record CombatIntentPayload(
        long attackId,
        UUID targetId,
        int zoneOrdinal,
        double hitRatio,
        double lateral,
        double distance
) implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 1;
    public static final Type<CombatIntentPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "combat_intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatIntentPayload> CODEC = StreamCodec.ofMember(
            CombatIntentPayload::write,
            CombatIntentPayload::read
    );

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeInt(PROTOCOL_VERSION);
        buf.writeLong(attackId);
        buf.writeLong(targetId.getMostSignificantBits());
        buf.writeLong(targetId.getLeastSignificantBits());
        buf.writeInt(zoneOrdinal);
        buf.writeDouble(hitRatio);
        buf.writeDouble(lateral);
        buf.writeDouble(distance);
    }

    private static CombatIntentPayload read(RegistryFriendlyByteBuf buf) {
        int version = buf.readInt();
        long attackId = buf.readLong();
        UUID targetId = new UUID(buf.readLong(), buf.readLong());
        int zoneOrdinal = buf.readInt();
        double hitRatio = buf.readDouble();
        double lateral = buf.readDouble();
        double distance = buf.readDouble();
        return new CombatIntentPayload(attackId, targetId, zoneOrdinal, hitRatio, lateral, distance);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
