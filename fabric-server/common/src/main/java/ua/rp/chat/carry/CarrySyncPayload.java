package ua.rp.chat.carry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CarrySyncPayload(String json) implements CustomPacketPayload {
 public static final int MAX_BYTES = 1048576;
 public static final Type<CarrySyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "carry_sync"));
 public static final StreamCodec<RegistryFriendlyByteBuf, CarrySyncPayload> CODEC = StreamCodec.ofMember(
   CarrySyncPayload::write, CarrySyncPayload::read);
 public CarrySyncPayload { CarryWire.utf8(json, MAX_BYTES); }
 private void write(RegistryFriendlyByteBuf buffer) { buffer.writeByteArray(CarryWire.utf8(json, MAX_BYTES)); }
 private static CarrySyncPayload read(RegistryFriendlyByteBuf buffer) {
  return new CarrySyncPayload(CarryWire.decode(buffer.readByteArray(MAX_BYTES), MAX_BYTES));
 }
 @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
