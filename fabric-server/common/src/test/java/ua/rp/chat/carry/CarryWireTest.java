package ua.rp.chat.carry;
public final class CarryWireTest {
 public static void main(String[] args) {
  require(CarryWire.utf8("ёж", 4).length == 4);
  fails(() -> CarryWire.utf8(null, 10));
  fails(() -> CarryWire.utf8("abc", 2));
  fails(() -> CarryWire.utf8("\ud800", 10));
  fails(() -> CarryWire.decode(new byte[]{(byte)0xc3, 0x28}, 10));
  fails(() -> CarryWire.decode(new byte[11], 10));
  require(CarryWire.decode(CarryWire.utf8("ёж", 4), 4).equals("ёж"));
  System.out.println("CarryWireTest passed");
 }
 private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
 private static void fails(Runnable r) { try { r.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected strict wire rejection"); }
}
