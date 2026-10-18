package ua.rp.chat.carry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CarryActionPayload(String json) implements CustomPacketPayload {
 public static final int MAX_BYTES = 4096;
 public static final Type<CarryActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "carry_action"));
 public static final StreamCodec<RegistryFriendlyByteBuf, CarryActionPayload> CODEC = StreamCodec.ofMember(
   CarryActionPayload::write, CarryActionPayload::read);
 public CarryActionPayload { CarryWire.utf8(json, MAX_BYTES); }
 private void write(RegistryFriendlyByteBuf buffer) { buffer.writeByteArray(CarryWire.utf8(json, MAX_BYTES)); }
 private static CarryActionPayload read(RegistryFriendlyByteBuf buffer) {
  return new CarryActionPayload(CarryWire.decode(buffer.readByteArray(MAX_BYTES), MAX_BYTES));
 }
 @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
