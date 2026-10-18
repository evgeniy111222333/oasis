package ua.rp.chat.modeleditor;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Exercises the real packet codecs, including byte lengths and malicious frame headers. */
public final class ModelEditorWireTest {
    public static void main(String[] args) {
        String unicode = "{\"action\":\"open\",\"message\":\"Русский текст\"}";
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            ModelEditorRequestPayload.CODEC.encode(buffer, new ModelEditorRequestPayload(unicode));
            require(ModelEditorRequestPayload.CODEC.decode(buffer).json().equals(unicode), "Request UTF-8 must round-trip");
            require(buffer.readableBytes() == 0, "Codec must consume exactly one frame");
            ModelEditorSyncPayload.CODEC.encode(buffer, new ModelEditorSyncPayload(unicode));
            require(ModelEditorSyncPayload.CODEC.decode(buffer).json().equals(unicode), "Sync UTF-8 must round-trip");
        } finally { buffer.release(); }
        // Cyrillic is two UTF-8 bytes: a character-only cap would incorrectly allow this packet.
        rejects(() -> new ModelEditorRequestPayload("я".repeat(15_001)));
        rejects(() -> new ModelEditorSyncPayload("я".repeat(16_001)));
        buffer = buffer();
        try {
            String maximum = "я".repeat(15_000);
            ModelEditorRequestPayload.CODEC.encode(buffer, new ModelEditorRequestPayload(maximum));
            require(ModelEditorRequestPayload.CODEC.decode(buffer).json().equals(maximum), "Exact byte bound must round-trip");
        } finally { buffer.release(); }
        RegistryFriendlyByteBuf oversized = buffer();
        try {
            oversized.writeVarInt(ModelEditorRequestPayload.MAX_BYTES + 1);
            rejects(() -> ModelEditorRequestPayload.CODEC.decode(oversized));
        } finally { oversized.release(); }
        RegistryFriendlyByteBuf negative = buffer();
        try {
            negative.writeVarInt(-1);
            rejects(() -> ModelEditorRequestPayload.CODEC.decode(negative));
        } finally { negative.release(); }
        RegistryFriendlyByteBuf damaged = buffer();
        try {
            damaged.writeByteArray(new byte[]{(byte) 0xc3, 0x28});
            rejects(() -> ModelEditorRequestPayload.CODEC.decode(damaged));
        } finally { damaged.release(); }
        System.out.println("ModelEditorWireTest passed: actual bounded UTF-8 request/sync codecs");
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected malformed or oversized packet rejection");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
