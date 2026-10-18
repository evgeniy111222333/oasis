package ua.rp.chat.modeleditor;

import net.minecraft.network.RegistryFriendlyByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

final class ModelEditorWire {
    private ModelEditorWire() {}
    static void write(RegistryFriendlyByteBuf buffer, String json, int maximumBytes) {
        ModelEditorJson.bounded(json, maximumBytes);
        buffer.writeByteArray(json.getBytes(StandardCharsets.UTF_8));
    }
    static String read(RegistryFriendlyByteBuf buffer, int maximumBytes) {
        byte[] bytes = buffer.readByteArray(maximumBytes);
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException damaged) { throw new IllegalArgumentException("Malformed model editor UTF-8", damaged); }
    }
}
