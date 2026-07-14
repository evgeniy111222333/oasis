package ua.rp.chat.microvoxel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MicrovoxelProtocol {
    public static final String SYNC_CHANNEL = "rpchat:microvoxels";
    public static final String ACTION_CHANNEL = "rpchat:microvoxel_action";
    public static final int CLEAR = 1;
    public static final int UPSERT = 2;
    public static final int REMOVE = 3;
    public static final int MESSAGE = 4;
    public static final int ACTION_CONVERT = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_ADD = 3;

    private MicrovoxelProtocol() {
    }

    public static byte[] clear() {
        return new byte[]{CLEAR};
    }

    public static byte[] remove(MicrovoxelKey key) {
        return write(output -> {
            output.writeByte(REMOVE);
            writePosition(output, key);
        });
    }

    public static byte[] message(String message) {
        return write(output -> {
            output.writeByte(MESSAGE);
            writeUtf8(output, message);
        });
    }

    public static byte[] upsert(MicrovoxelKey key, MicrovoxelVolume volume) {
        return write(output -> {
            output.writeByte(UPSERT);
            writePosition(output, key);
            output.writeInt(volume.revision());
            output.writeByte(volume.palette().size());
            for (String material : volume.palette()) writeUtf8(output, material);

            byte[] cells = volume.cellsCopy();
            int runs = 0;
            for (int index = 0; index < cells.length;) {
                byte material = cells[index];
                int end = index + 1;
                while (end < cells.length && cells[end] == material && end - index < 65535) end++;
                runs++;
                index = end;
            }
            boolean useRuns = runs * 3 + 2 < cells.length;
            output.writeByte(useRuns ? 1 : 0);
            if (useRuns) {
                output.writeShort(runs);
                for (int index = 0; index < cells.length;) {
                    byte material = cells[index];
                    int end = index + 1;
                    while (end < cells.length && cells[end] == material && end - index < 65535) end++;
                    output.writeShort(end - index);
                    output.writeByte(material);
                    index = end;
                }
            } else {
                output.write(cells);
            }
        });
    }

    private static void writePosition(DataOutputStream output, MicrovoxelKey key) throws IOException {
        output.writeInt(key.x());
        output.writeInt(key.y());
        output.writeInt(key.z());
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("UTF-8 value is too long");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static byte[] write(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
