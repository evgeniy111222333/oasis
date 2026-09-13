package ua.rp.chat.client.blood;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Compact bidirectional footprint protocol. Clients send REQUEST packets;
 * the server validates, persists and broadcasts STAMP/REMOVE packets.
 */
public record BloodFootprintPayload(
        int event,
        long decalId,
        int entityId,
        UUID playerUuid,
        int sequence,
        double x,
        double y,
        double z,
        float yaw,
        float wetness,
        int foot,
        int gait,
        int material,
        int footwear,
        long seed,
        int ageTicks,
        int lifetimeTicks
) implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 1;
    public static final int REQUEST = 0;
    public static final int STAMP = 1;
    public static final int REMOVE = 2;
    public static final int ABSORB = 3;
    public static final int UPDATE = 4;

    public static final Type<BloodFootprintPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "blood_footprint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BloodFootprintPayload> CODEC =
            StreamCodec.ofMember(BloodFootprintPayload::write, BloodFootprintPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(PROTOCOL_VERSION);
        buffer.writeVarInt(event);
        buffer.writeLong(decalId);
        buffer.writeVarInt(entityId);
        buffer.writeUUID(playerUuid == null ? new UUID(0L, 0L) : playerUuid);
        buffer.writeVarInt(sequence);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(yaw);
        buffer.writeFloat(wetness);
        buffer.writeVarInt(foot);
        buffer.writeVarInt(gait);
        buffer.writeVarInt(material);
        buffer.writeVarInt(footwear);
        buffer.writeLong(seed);
        buffer.writeVarInt(ageTicks);
        buffer.writeVarInt(lifetimeTicks);
    }

    private static BloodFootprintPayload read(RegistryFriendlyByteBuf buffer) {
        int version = buffer.readVarInt();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported blood-footprint protocol " + version);
        }
        return new BloodFootprintPayload(
                buffer.readVarInt(),
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readUUID(),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
