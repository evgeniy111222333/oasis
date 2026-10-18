package ua.rp.chat.carry;
import ua.rp.chat.microvoxel.*;
/** Exact quarter turns of material cells and authored sub-cell geometry. */
public final class CarryVoxelRotation {
 private CarryVoxelRotation(){}
 public static MicrovoxelVolume rotate(MicrovoxelVolume source,int turns){
  int count=Math.floorMod(turns,4);if(count==0)return source.copy();MicrovoxelVolume result=source;
  for(int i=0;i<count;i++)result=quarter(result);return result;
 }
 private static MicrovoxelVolume quarter(MicrovoxelVolume source){
  byte[] old=source.cellsCopy(),cells=new byte[4096];MicrovoxelGeometry geometry=MicrovoxelGeometry.empty();
  for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
   int src=MicrovoxelVolume.index(x,y,z),dst=MicrovoxelVolume.index(15-z,y,x);cells[dst]=old[src];
   int shape=source.shapeAt(src);if(shape!=0)geometry.setShape(dst,rotatedShape(shape));
  }
  return MicrovoxelVolume.restore(source.revision(),source.palette(),cells,geometry.isEmpty()?null:geometry);
 }
 private static final java.util.Map<Integer,Integer> SHAPES=new java.util.concurrent.ConcurrentHashMap<>();
 private static int rotatedShape(int id){return SHAPES.computeIfAbsent(id,key->{
  MicrovoxelShape source=MicrovoxelShape.byId(key);
  for(int candidate=0;candidate<MicrovoxelShape.count();candidate++){
   MicrovoxelShape target=MicrovoxelShape.byId(candidate);boolean same=true;
   outer:for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(source.occupied(x,y,z)!=target.occupied(15-z,y,x)){same=false;break outer;}
   if(same)return candidate;
  }
  throw new IllegalArgumentException("Shape has no exact quarter-turn representation");
 });}
}
