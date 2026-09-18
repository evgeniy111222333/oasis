package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelGeometry;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.MicrovoxelWire;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire dispatcher for the microvoxel sync channel, extracted from {@link MicrovoxelClientState}.
 *
 * <p>Reads one length-prefixed {@link MicrovoxelWire} frame and routes it to the appropriate
 * extracted subsystem: snapshot lifecycle and control actions back to the facade, edits to
 * {@link MicrovoxelClientPrediction}, fluid to the fluid mirror, crack stages to
 * {@link MicrovoxelClientCracks}. An unknown frame type is skipped by length and never desyncs the
 * stream. The facade keeps a thin {@code handle} delegate so the payload registration is unchanged.
 */
public final class MicrovoxelClientSync {
    private static final int CLEAR = MicrovoxelWire.CLEAR;
    private static final int UPSERT = MicrovoxelWire.UPSERT;
    private static final int REMOVE = MicrovoxelWire.REMOVE;
    private static final int MESSAGE = MicrovoxelWire.MESSAGE;
    private static final int BATCH_UPSERT = MicrovoxelWire.BATCH_UPSERT;
    private static final int CLEAR_CHUNK = MicrovoxelWire.CLEAR_CHUNK;
    private static final int DELTA_UPSERT = MicrovoxelWire.DELTA_UPSERT;
    private static final int TRANSACTION = MicrovoxelWire.TRANSACTION;
    private static final int EDIT_RESULT = MicrovoxelWire.EDIT_RESULT;
    private static final int SNAPSHOT_BEGIN = MicrovoxelWire.SNAPSHOT_BEGIN;
    private static final int SNAPSHOT_END = MicrovoxelWire.SNAPSHOT_END;
    private static final int MINE_STAGE = MicrovoxelWire.MINE_STAGE;
    private static final int FLUID_UPSERT = MicrovoxelWire.FLUID_UPSERT;
    private static final int FLUID_REMOVE = MicrovoxelWire.FLUID_REMOVE;
    private static final int HELLO_ACK = MicrovoxelWire.HELLO_ACK;
    private static final int ACTION_SNAPSHOT_ACK = MicrovoxelWire.ACTION_SNAPSHOT_ACK;

    private MicrovoxelClientSync() {
    }

    /** One decoded transaction change: a volume, or null for a removal. */
    record TransactionChange(BlockPos position, MicrovoxelVolume volume) {
    }

    public static void handle(MicrovoxelSyncPayload payload) {
        // Shared length-prefixed frame: an unknown type is skipped defensively, never desyncs.
        MicrovoxelWire.Frame frame;
        try {
            frame = MicrovoxelWire.readFrame(payload.data());
        } catch (IOException rejected) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Rejected sync payload: " + rejected.getMessage());
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            int type = frame.type();
            if (type == HELLO_ACK) {
                int serverMajor = MicrovoxelClientCodec.readVarInt(input);
                int serverMinor = MicrovoxelClientCodec.readVarInt(input);
                MicrovoxelClientState.setNegotiatedCapabilities(MicrovoxelClientCodec.readVarInt(input));
                MicrovoxelClientState.setSessionNonce(input.readLong());
                if (serverMajor != MicrovoxelWire.MAJOR) {
                    EclipseClientMod.LOGGER.warn("[MICROVOXEL] Incompatible server protocol "
                            + serverMajor + "." + serverMinor + " (client "
                            + MicrovoxelWire.MAJOR + "." + MicrovoxelWire.MINOR + ")");
                } else if (MicrovoxelClientState.debug()) {
                    EclipseClientMod.LOGGER.info("[MICROVOXEL] Negotiated protocol major="
                            + serverMajor + "." + serverMinor);
                }
                return;
            }
            if (type == SNAPSHOT_BEGIN) {
                long snapshotId = input.readLong();
                if (snapshotId <= 0L || input.available() != 0) {
                    throw new IOException("Invalid snapshot-begin payload");
                }
                MicrovoxelClientState.clearVolumes();
                MicrovoxelClientState.setActiveSnapshotId(snapshotId);
                MicrovoxelClientState.setSnapshotConfirmed(false);
                return;
            }
            if (type == SNAPSHOT_END) {
                long snapshotId = input.readLong();
                if (input.available() != 0) {
                    throw new IOException("Trailing snapshot-end payload");
                }
                if (snapshotId != MicrovoxelClientState.activeSnapshotId()) {
                    MicrovoxelClientState.requestFullResync("snapshot end does not match active snapshot");
                    return;
                }
                MicrovoxelClientState.sendControlAction(ACTION_SNAPSHOT_ACK, snapshotId, 0, 0, 0);
                MicrovoxelClientState.setSnapshotConfirmed(true);
                MicrovoxelClientState.setActiveSnapshotId(-1L);
                return;
            }
            if (type == CLEAR) {
                MicrovoxelClientState.clearVolumes();
                return;
            }
            if (type == REMOVE) {
                BlockPos position = MicrovoxelClientCodec.readPosition(input);
                MicrovoxelClientState.acceptAuthoritative(position, null);
                return;
            }
            if (type == FLUID_UPSERT) {
                BlockPos position = MicrovoxelClientCodec.readPosition(input).immutable();
                int revision = MicrovoxelClientCodec.readVarInt(input);
                int kindCode = input.readUnsignedByte();
                if (kindCode != 0 && kindCode != 1) throw new IOException("Unknown fluid kind");
                int encodedBytes = MicrovoxelClientCodec.readVarInt(input);
                byte[] encoded = input.readNBytes(encodedBytes);
                if (encoded.length != encodedBytes) throw new EOFException("Truncated fluid levels");
                if (input.available() != 0) throw new IOException("Trailing fluid payload bytes");
                MicrovoxelClientState.acceptFluid(position, revision, kindCode == 1,
                        MicrovoxelClientCodec.decodeLevels(encoded));
                return;
            }
            if (type == FLUID_REMOVE) {
                BlockPos position = MicrovoxelClientCodec.readPosition(input);
                if (input.available() != 0) throw new IOException("Trailing fluid-remove bytes");
                MicrovoxelClientState.dropFluid(position.immutable());
                return;
            }
            if (type == MESSAGE) {
                String message = MicrovoxelClientCodec.readUtf8(input);
                if (input.available() != 0) throw new IOException("Trailing message payload bytes");
                Minecraft minecraft = Minecraft.getInstance();
                // GUI state must be touched on the game thread; this decoder runs on the network
                // receiver, so marshal the overlay update instead of calling in directly.
                minecraft.execute(() ->
                        minecraft.gui.setOverlayMessage(Component.literal(message), false));
                return;
            }
            if (type == CLEAR_CHUNK) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                MicrovoxelClientState.clearChunk(chunkX, chunkZ);
                return;
            }
            if (type == TRANSACTION) {
                long transactionId = input.readLong();
                int size = MicrovoxelClientCodec.readVarInt(input);
                if (size < 1 || size > 4096) throw new IOException("Invalid transaction size");
                List<TransactionChange> changes = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    BlockPos position = MicrovoxelClientCodec.readPosition(input).immutable();
                    MicrovoxelVolume volume = input.readBoolean()
                            ? MicrovoxelClientCodec.readRawVolume(input) : null;
                    changes.add(new TransactionChange(position, volume));
                }
                if (input.available() != 0) throw new IOException("Trailing transaction bytes");
                // Decode and validate the complete packet before exposing any part of the edit.
                for (TransactionChange change : changes) applyTransactionChange(change);
                if (MicrovoxelClientState.debug()) {
                    EclipseClientMod.LOGGER.info("[MICROVOXEL] Applied transaction {} ({} volumes)",
                            transactionId, changes.size());
                }
                return;
            }
            if (type == EDIT_RESULT) {
                long transactionId = input.readLong();
                boolean accepted = input.readBoolean();
                BlockPos position = MicrovoxelClientCodec.readPosition(input).immutable();
                MicrovoxelVolume volume = input.readBoolean()
                        ? MicrovoxelClientCodec.readRawVolume(input) : null;
                if (input.available() != 0) throw new IOException("Trailing edit-result bytes");
                MicrovoxelClientPrediction.reconcileEdit(transactionId, accepted, position, volume);
                return;
            }
            if (type == DELTA_UPSERT) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int posXZ = input.readUnsignedByte();
                int posY = input.readShort();
                int revision = MicrovoxelClientCodec.readVarInt(input);
                int cellIndex = MicrovoxelClientCodec.readVarInt(input);
                String material = MicrovoxelClientCodec.readUtf8(input);

                int x = (chunkX << 4) | ((posXZ >> 4) & 15);
                int z = (chunkZ << 4) | (posXZ & 15);
                BlockPos immutable = new BlockPos(x, posY, z).immutable();

                MicrovoxelVolume base = MicrovoxelClientVolumeStore.AUTHORITATIVE_VOLUMES.get(immutable);
                if (base == null) {
                    MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
                    base = cached == null ? null : cached.volume;
                }
                if (base == null) {
                    MicrovoxelClientState.requestVolumeResync(immutable, "delta without base volume");
                    return;
                }
                if (!MicrovoxelRevision.isNewer(revision, base.revision())) return;
                if (!MicrovoxelRevision.isImmediateNext(revision, base.revision())) {
                    MicrovoxelClientState.requestVolumeResync(immutable, "delta revision gap local="
                            + base.revision() + ", remote=" + revision);
                    return;
                }
                MicrovoxelVolume authoritative = base.copy();
                try {
                    authoritative.update(cellIndex, material);
                } catch (IllegalStateException paletteFull) {
                    MicrovoxelClientState.requestVolumeResync(immutable,
                            "delta needs new material but palette is full");
                    return;
                }
                authoritative.setRevision(revision);
                MicrovoxelClientState.acceptAuthoritative(immutable, authoritative);
                return;
            }
            if (type == BATCH_UPSERT) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int size = MicrovoxelClientCodec.readVarInt(input);
                if (size < 0 || size > MicrovoxelClientState.maxBatchEntries()) {
                    throw new IOException("Invalid microvoxel batch size " + size);
                }
                for (int entryIdx = 0; entryIdx < size; entryIdx++) {
                    int posXZ = input.readUnsignedByte();
                    int posY = input.readShort();
                    int x = (chunkX << 4) | ((posXZ >> 4) & 15);
                    int z = (chunkZ << 4) | (posXZ & 15);
                    BlockPos position = new BlockPos(x, posY, z);

                    int revision = MicrovoxelClientCodec.readVarInt(input);
                    int paletteSize = MicrovoxelClientCodec.readVarInt(input);
                    if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
                        throw new IOException("Invalid microvoxel batch palette size " + paletteSize);
                    }
                    List<String> palette = new ArrayList<>(paletteSize);
                    for (int index = 0; index < paletteSize; index++) {
                        palette.add(MicrovoxelClientCodec.readUtf8(input));
                    }

                    byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
                    int encoding = input.readUnsignedByte();
                    if (encoding == 0) {
                        if (input.readNBytes(cells, 0, cells.length) != cells.length) {
                            throw new EOFException("Truncated cells");
                        }
                    } else if (encoding == 1) {
                        int runs = MicrovoxelClientCodec.readVarInt(input);
                        int cursor = 0;
                        for (int run = 0; run < runs; run++) {
                            int length = MicrovoxelClientCodec.readVarInt(input);
                            byte material = input.readByte();
                            if (length < 1 || cursor + length > cells.length) {
                                throw new IOException("Invalid RLE run");
                            }
                            java.util.Arrays.fill(cells, cursor, cursor + length, material);
                            cursor += length;
                        }
                        if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
                    } else {
                        throw new IOException("Unknown cell encoding");
                    }

                    MicrovoxelVolume volume = new MicrovoxelVolume(revision, palette, cells,
                            MicrovoxelClientCodec.readOptionalGeometry(input));
                    BlockPos immutable = position.immutable();
                    if (!MicrovoxelClientPrediction.isFreshAuthoritative(immutable, revision)) continue;
                    MicrovoxelClientState.acceptAuthoritative(immutable, volume);
                }
                return;
            }
            if (type == MINE_STAGE) {
                BlockPos position = MicrovoxelClientCodec.readPosition(input).immutable();
                int cell = MicrovoxelClientCodec.readVarInt(input);
                int stage = input.readByte();
                if (input.available() != 0) throw new IOException("Trailing mine-stage bytes");
                MicrovoxelClientState.applyMineStage(position, cell, stage);
                return;
            }
            if (type != UPSERT) {
                if (MicrovoxelClientState.debug()) {
                    EclipseClientMod.LOGGER.debug("[MICROVOXEL] Ignored message type " + type);
                }
                return;
            }
            long tStart = System.nanoTime();
            BlockPos position = MicrovoxelClientCodec.readPosition(input);
            int revision = MicrovoxelClientCodec.readVarInt(input);
            int paletteSize = MicrovoxelClientCodec.readVarInt(input);
            if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
                throw new IOException("Invalid palette size");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) {
                palette.add(MicrovoxelClientCodec.readUtf8(input));
            }
            byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
            int encoding = input.readUnsignedByte();
            long tDecodeStart = System.nanoTime();
            if (encoding == 0) {
                if (input.readNBytes(cells, 0, cells.length) != cells.length) {
                    throw new EOFException("Truncated cells");
                }
            } else if (encoding == 1) {
                int runs = MicrovoxelClientCodec.readVarInt(input);
                int cursor = 0;
                for (int run = 0; run < runs; run++) {
                    int length = MicrovoxelClientCodec.readVarInt(input);
                    byte material = input.readByte();
                    if (length < 1 || cursor + length > cells.length) {
                        throw new IOException("Invalid RLE run");
                    }
                    java.util.Arrays.fill(cells, cursor, cursor + length, material);
                    cursor += length;
                }
                if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
            } else {
                throw new IOException("Unknown cell encoding");
            }
            long tDecodeEnd = System.nanoTime();
            MicrovoxelGeometry geometry = MicrovoxelClientCodec.readOptionalGeometry(input);
            if (input.available() != 0) throw new IOException("Trailing microvoxel payload bytes");
            MicrovoxelVolume volume = new MicrovoxelVolume(revision, palette, cells, geometry);
            BlockPos immutable = position.immutable();
            if (!MicrovoxelClientPrediction.isFreshAuthoritative(immutable, revision)) return;
            MicrovoxelCachedVolume oldCached = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
            long tRebuildStart = System.nanoTime();
            MicrovoxelClientState.acceptAuthoritative(immutable, volume);
            long tRebuildEnd = System.nanoTime();

            if (MicrovoxelClientState.debug()) {
                double totalUs = (System.nanoTime() - tStart) / 1000.0;
                double decodeUs = (tDecodeEnd - tDecodeStart) / 1000.0;
                double rebuildUs = (tRebuildEnd - tRebuildStart) / 1000.0;
                MicrovoxelCachedVolume now = MicrovoxelClientVolumeStore.VOLUMES.get(immutable);
                EclipseClientMod.LOGGER.info("[MICROVOXEL-PERF] SYNC_UPSERT pos={} | Total: {}us "
                                + "(Decode: {}us, Rebuild: {}us) | Faces: {} | BoundaryTouch: {}",
                        immutable.toShortString(),
                        String.format("%.2f", totalUs),
                        String.format("%.2f", decodeUs),
                        String.format("%.2f", rebuildUs),
                        now == null ? 0 : now.mesh.size(),
                        oldCached == null || MicrovoxelClientState.changesTouchBoundary(oldCached.volume, volume));
            }
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("[MICROVOXEL] Rejected sync payload: " + error.getMessage());
        }
    }

    private static void applyTransactionChange(TransactionChange change) {
        MicrovoxelVolume volume = change.volume();
        if (volume != null
                && !MicrovoxelClientPrediction.isFreshAuthoritative(change.position(), volume.revision())) {
            return;
        }
        MicrovoxelClientState.acceptAuthoritative(change.position(), volume);
    }
}
