package ua.rp.chat.microvoxel;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict decoder for the portable microvoxel payload stored on carved block items.
 *
 * <p>The format is deliberately independent from client rendering. Both placement prediction
 * and the item model consume this one validated representation, so the shape shown in the hand
 * cannot drift away from the shape placed in the world.</p>
 */
public final class MicrovoxelPortableVolume {
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_MATERIAL_BYTES = 8_192;

    private MicrovoxelPortableVolume() {
    }

    public static MicrovoxelVolume decode(byte[] bytes) throws IOException {
        if (bytes == null
                || bytes.length < MicrovoxelVolume.CELL_COUNT + Integer.BYTES + 1
                || bytes.length > MAX_BYTES) {
            throw new IOException("Invalid portable microvoxel payload size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int revision = input.readInt();
            int paletteSize = input.readUnsignedByte();
            if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
                throw new IOException("Invalid portable microvoxel palette size");
            }

            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) {
                int length = input.readUnsignedShort();
                if (length > MAX_MATERIAL_BYTES || length > input.available()) {
                    throw new EOFException("Truncated portable microvoxel material");
                }
                byte[] encoded = input.readNBytes(length);
                String material = new String(encoded, StandardCharsets.UTF_8);
                if ((index == 0 && !material.isEmpty())
                        || (index > 0 && material.isBlank())) {
                    throw new IOException("Invalid portable microvoxel material");
                }
                palette.add(material);
            }

            byte[] cells = input.readNBytes(MicrovoxelVolume.CELL_COUNT);
            if (cells.length != MicrovoxelVolume.CELL_COUNT || input.available() != 0) {
                throw new IOException("Truncated or trailing portable microvoxel cells");
            }
            return new MicrovoxelVolume(revision, palette, cells);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid portable microvoxel volume", error);
        }
    }

    /**
     * Builds a deterministic compact remainder representation when an item stores only a material
     * amount rather than an authored carved shape. The exact occupied count is preserved.
     */
    public static MicrovoxelVolume packedRemainder(String material, int occupiedCells) {
        if (material == null || material.isBlank()) {
            throw new IllegalArgumentException("Material cannot be empty");
        }
        int count = Math.max(1, Math.min(MicrovoxelVolume.CELL_COUNT, occupiedCells));
        byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
        for (int index = 0; index < count; index++) cells[index] = 1;
        return new MicrovoxelVolume(1, List.of("", material), cells);
    }
}
