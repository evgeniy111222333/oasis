package ua.rp.chat.carry;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.io.*;
/** Complete native block entity serialization shared by world mutations and headless verification. */
public final class CarryBlockSnapshot {
 private CarryBlockSnapshot(){}
 public static byte[] encode(BlockEntity entity,HolderLookup.Provider registries)throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.write(entity.saveWithFullMetadata(registries),new DataOutputStream(bytes));if(bytes.size()>2_097_152)throw new IOException("Container snapshot exceeds limit");return bytes.toByteArray();
 }
 public static BlockEntity decode(byte[] bytes,BlockPos pos,BlockState state,HolderLookup.Provider registries)throws IOException {
  if(bytes.length>2_097_152)throw new IOException("Container snapshot exceeds limit");
  DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes));CompoundTag tag=NbtIo.read(input,NbtAccounter.create(2_097_152));if(input.read()!=-1)throw new IOException("Trailing block entity bytes");
  tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());BlockEntity entity=BlockEntity.loadStatic(pos,state,tag,registries);if(entity==null)throw new IOException("Unable to restore block entity");return entity;
 }
}
