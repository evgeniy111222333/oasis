package ua.rp.chat.carry;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8 shared by both bounded carry packet codecs. */
public final class CarryWire {
 private CarryWire() {}
 public static byte[] utf8(String value, int limit) {
  if (value == null || value.length() > limit) throw new IllegalArgumentException("Carry payload is null or oversized");
  try {
   ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
     .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
   if (bytes.remaining() > limit) throw new IllegalArgumentException("Oversized carry payload");
   byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
  } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Malformed carry UTF-8", e); }
 }
 public static String decode(byte[] bytes, int limit) {
  if (bytes == null || bytes.length > limit) throw new IllegalArgumentException("Carry payload is null or oversized");
  try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
  } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Malformed carry UTF-8", e); }
 }
}
