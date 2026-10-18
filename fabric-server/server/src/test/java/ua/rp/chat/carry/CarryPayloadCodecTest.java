package ua.rp.chat.carry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
public final class CarryPayloadCodecTest {
 public static void main(String[] args) {
  RegistryFriendlyByteBuf b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
  try {
   CarryActionPayload.CODEC.encode(b,new CarryActionPayload("{\"action\":\"hold\"}"));
   require(CarryActionPayload.CODEC.decode(b).json().contains("hold"));b.clear();
   b.writeByteArray(new byte[]{(byte)0xc3,0x28});fails(()->CarryActionPayload.CODEC.decode(b));b.clear();
   b.writeVarInt(4097);fails(()->CarryActionPayload.CODEC.decode(b));b.clear();
   b.writeVarInt(-1);fails(()->CarryActionPayload.CODEC.decode(b));b.clear();
   fails(()->new CarryActionPayload("x".repeat(4097)));
   fails(()->new CarrySyncPayload(null));
   String limit="x".repeat(CarrySyncPayload.MAX_BYTES);CarrySyncPayload.CODEC.encode(b,new CarrySyncPayload(limit));require(CarrySyncPayload.CODEC.decode(b).json().equals(limit));
   fails(()->new CarrySyncPayload(limit+"x"));
  }finally{b.release();}
  System.out.println("CarryPayloadCodecTest passed");
 }
 static void require(boolean ok){if(!ok)throw new AssertionError();}
 static void fails(Runnable action){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError("Expected codec rejection");}
}
