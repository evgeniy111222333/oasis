package ua.rp.chat.carry;
import ua.rp.chat.microvoxel.*;
public final class CarryVoxelRotationTest {
 public static void main(String[] args) {
  byte[] cells=new byte[4096];cells[MicrovoxelVolume.index(2,3,4)]=1;
  MicrovoxelVolume original=new MicrovoxelVolume(12,java.util.List.of("","minecraft:stone"),cells);
  original.setShape(MicrovoxelVolume.index(2,3,4),MicrovoxelShape.Type.RAMP_S.ordinal());
  MicrovoxelVolume rotated=CarryVoxelRotation.rotate(original,1);
  require(rotated.materialAt(11,3,2)==1,"cell rotation");
  MicrovoxelVolume back=CarryVoxelRotation.rotate(rotated,3);
  require(java.util.Arrays.equals(back.cellsCopy(),original.cellsCopy()),"material round trip");
  require(back.shapeAt(MicrovoxelVolume.index(2,3,4))==original.shapeAt(MicrovoxelVolume.index(2,3,4)),"shape round trip");
  require(original.materialAt(2,3,4)==1,"immutable source");
  System.out.println("CarryVoxelRotationTest passed");
 }
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
