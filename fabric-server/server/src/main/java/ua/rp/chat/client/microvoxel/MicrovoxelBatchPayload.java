package ua.rp.chat.client.microvoxel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server batch of microvoxel edit actions sharing one look/eye sample. Ten clicks in
 * one 50ms window travel as one packet instead of ten; every entry keeps its own transaction
 * id, so authoritative edit results still match 1:1 and per-edit validation (reach, revision,
 * raycast, economy) is unchanged.
 *
 * <p>Mirror contract: this file is duplicated verbatim in the server module. The wire layout
 * mirrors {@link MicrovoxelActionPayload} field for field; any change must be applied to both
 * copies at once.</p>
 */
public record MicrovoxelBatchPayload(int protocolVersion, long batchId, List<Entry> entries,
                                     float lookX, float lookY, float lookZ,
                                     float eyeX, float eyeY, float eyeZ)
        implements CustomPacketPayload {
    /** Hard cap per packet: bounds server work per batch and keeps packets small. */
    public static final int MAX_ENTRIES = 16;

    public static final Type<MicrovoxelBatchPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "microvoxel_batch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MicrovoxelBatchPayload> CODEC = StreamCodec.ofMember(
            MicrovoxelBatchPayload::write, MicrovoxelBatchPayload::read);

    /** One batched edit. Field order matches the single-action payload exactly. */
    public record Entry(int action, int x, int y, int z, int cell, int revision, long transactionId) {
    }

    /**
     * Splits an ordered entry list into transmission chunks of at most {@code maxPerBatch}.
     * Pure and order-preserving: unit-tested without Minecraft.
     */
    public static List<List<Entry>> split(List<Entry> entries, int maxPerBatch) {
        List<List<Entry>> batches = new ArrayList<>();
        if (entries == null || entries.isEmpty() || maxPerBatch < 1) return batches;
        for (int from = 0; from < entries.size(); from += maxPerBatch) {
            batches.add(List.copyOf(entries.subList(from, Math.min(from + maxPerBatch, entries.size()))));
        }
        return batches;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(protocolVersion);
        buffer.writeVarLong(batchId);
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeByte(entry.action());
            buffer.writeInt(entry.x());
            buffer.writeInt(entry.y());
            buffer.writeInt(entry.z());
            buffer.writeShort(entry.cell());
            buffer.writeInt(entry.revision());
            buffer.writeVarLong(entry.transactionId());
        }
        buffer.writeFloat(lookX);
        buffer.writeFloat(lookY);
        buffer.writeFloat(lookZ);
        buffer.writeFloat(eyeX);
        buffer.writeFloat(eyeY);
        buffer.writeFloat(eyeZ);
    }

    private static MicrovoxelBatchPayload read(RegistryFriendlyByteBuf buffer) {
        int version = buffer.readVarInt();
        long batch = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 1 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid microvoxel batch size " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(
                    buffer.readUnsignedByte(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readUnsignedShort(), buffer.readInt(), buffer.readVarLong()));
        }
        return new MicrovoxelBatchPayload(version, batch,
                List.copyOf(entries),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
