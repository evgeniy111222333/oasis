package ua.rp.chat.client.blood;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Authoritative persisted surface-film protocol. REQUEST/ABSORB travel to the server;
 * STAMP/UPDATE/REMOVE travel to tracking clients.
 */
public record BloodSurfacePayload(
        int event, long id, int sequence,
        double x, double y, double z,
        float nx, float ny, float nz,
        float volumeMl, float energy,
        int material, int family, long seed, int flowDepth,
        int ageTicks, int lifetimeTicks
) implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 1;
    public static final int REQUEST = 0;
    public static final int STAMP = 1;
    public static final int UPDATE = 2;
    public static final int REMOVE = 3;
    public static final int ABSORB = 4;

    public static final Type<BloodSurfacePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "blood_surface"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BloodSurfacePayload> CODEC =
            StreamCodec.ofMember(BloodSurfacePayload::write, BloodSurfacePayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(PROTOCOL_VERSION);
        buffer.writeVarInt(event);
        buffer.writeLong(id);
        buffer.writeVarInt(sequence);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(nx);
        buffer.writeFloat(ny);
        buffer.writeFloat(nz);
        buffer.writeFloat(volumeMl);
        buffer.writeFloat(energy);
        buffer.writeVarInt(material);
        buffer.writeVarInt(family);
        buffer.writeLong(seed);
        buffer.writeVarInt(flowDepth);
        buffer.writeVarInt(ageTicks);
        buffer.writeVarInt(lifetimeTicks);
    }

    private static BloodSurfacePayload read(RegistryFriendlyByteBuf buffer) {
        int version = buffer.readVarInt();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported blood-surface protocol " + version);
        }
        return new BloodSurfacePayload(buffer.readVarInt(), buffer.readLong(), buffer.readVarInt(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
