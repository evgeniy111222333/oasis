package ua.rp.chat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Compact, structured server-to-client RP feed. Vanilla chat is deliberately not used as UI transport. */
public final class RpChatFeedProtocol {
    public static final String CHANNEL = "rpchat:rp_feed";
    private static final int MAX_TEXT_BYTES = 720;

    private RpChatFeedProtocol() {
    }

    public static byte[] message(UUID speakerId, RpChatChannel channel, boolean distant, String name, String text) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(1);
                out.writeLong(speakerId == null ? 0L : speakerId.getMostSignificantBits());
                out.writeLong(speakerId == null ? 0L : speakerId.getLeastSignificantBits());
                out.writeByte(channel.ordinal());
                out.writeBoolean(distant);
                writeText(out, name);
                writeText(out, text);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).trim().getBytes(StandardCharsets.UTF_8);
        int length = Math.min(MAX_TEXT_BYTES, bytes.length);
        out.writeShort(length);
        out.write(bytes, 0, length);
    }
}
