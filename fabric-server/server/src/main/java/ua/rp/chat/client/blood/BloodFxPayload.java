package ua.rp.chat.client.blood;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Compact, server-authoritative wound event. The server sends wound semantics;
 * clients independently simulate the expensive droplets and decals.
 */
public record BloodFxPayload(
        int event,
        int entityId,
        UUID entityUuid,
        long woundId,
        int zone,
        int face,
        int profile,
        float localSide,
        float localHeight,
        float intensity,
        float bleeding,
        float impactVolumeMl,
        float flowMlPerSecond,
        float remainingBloodMl,
        float directionX,
        float directionY,
        float directionZ,
        float penetrationDepth,
        long seed,
        int revision,
        int flags
) implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 4;
    public static final int IMPACT = 1;
    public static final int WOUND_SYNC = 2;
    public static final int CLEAR = 3;

    public static final int FLAG_BANDAGED = 1;
    public static final int FLAG_EMBEDDED_PROJECTILE = 1 << 1;
    public static final int FLAG_OPEN_WOUND = 1 << 2;
    public static final int FLAG_PROJECTILE_EXIT = 1 << 3;
    public static final int FLAG_PROJECTILE_SHALLOW = 1 << 4;

    public static final Type<BloodFxPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "blood_fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BloodFxPayload> CODEC =
            StreamCodec.ofMember(BloodFxPayload::write, BloodFxPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(PROTOCOL_VERSION);
        buffer.writeByte(event);
        buffer.writeVarInt(entityId);
        buffer.writeLong(entityUuid.getMostSignificantBits());
        buffer.writeLong(entityUuid.getLeastSignificantBits());
        buffer.writeLong(woundId);
        buffer.writeByte(zone);
        buffer.writeByte(face);
        buffer.writeByte(profile);
        buffer.writeFloat(localSide);
        buffer.writeFloat(localHeight);
        buffer.writeFloat(intensity);
        buffer.writeFloat(bleeding);
        buffer.writeFloat(impactVolumeMl);
        buffer.writeFloat(flowMlPerSecond);
        buffer.writeFloat(remainingBloodMl);
        buffer.writeFloat(directionX);
        buffer.writeFloat(directionY);
        buffer.writeFloat(directionZ);
        buffer.writeFloat(penetrationDepth);
        buffer.writeLong(seed);
        buffer.writeVarInt(revision);
        buffer.writeByte(flags);
    }

    private static BloodFxPayload read(RegistryFriendlyByteBuf buffer) {
        int version = buffer.readVarInt();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported blood FX protocol: " + version);
        }
        int event = buffer.readUnsignedByte();
        int entityId = buffer.readVarInt();
        UUID uuid = new UUID(buffer.readLong(), buffer.readLong());
        long woundId = buffer.readLong();
        int zone = buffer.readByte();
        int face = buffer.readUnsignedByte();
        int profile = buffer.readUnsignedByte();
        float localSide = buffer.readFloat();
        float localHeight = buffer.readFloat();
        float intensity = buffer.readFloat();
        float bleeding = buffer.readFloat();
        float impactVolumeMl = buffer.readFloat();
        float flowMlPerSecond = buffer.readFloat();
        float remainingBloodMl = buffer.readFloat();
        float directionX = buffer.readFloat();
        float directionY = buffer.readFloat();
        float directionZ = buffer.readFloat();
        float penetrationDepth = buffer.readFloat();
        long seed = buffer.readLong();
        int revision = buffer.readVarInt();
        int flags = buffer.readUnsignedByte();
        return new BloodFxPayload(event, entityId, uuid, woundId, zone, face, profile, localSide, localHeight,
                intensity, bleeding, impactVolumeMl, flowMlPerSecond, remainingBloodMl,
                directionX, directionY, directionZ, penetrationDepth, seed, revision, flags);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
