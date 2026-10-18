package ua.rp.chat.carry;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.Container;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
public final class CarryBlockSnapshotTest {
 public static void main(String[] args)throws Exception {
  var provider=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  Items.SHULKER_BOX.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE,1).build());
  ItemStack named=new ItemStack(Items.APPLE,17);named.set(DataComponents.CUSTOM_NAME,Component.literal("Private cargo"));
  ItemStack box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(named.copy())));
  BlockState original=Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
  ChestBlockEntity chest=new ChestBlockEntity(new BlockPos(1,70,2),original);chest.setItem(0,named);chest.setItem(9,box);
  byte[] bytes=CarryBlockSnapshot.encode(chest,provider);
  String stateJson=BlockState.CODEC.encodeStart(JsonOps.INSTANCE,original).getOrThrow().toString();
  CarryLedger.FileStore store=new CarryLedger.FileStore(Files.createTempDirectory("carry-native").resolve("carry.bin"));CarryLedger ledger=new CarryLedger(store);
  UUID id=ledger.introduce(new CarryLedger.Snapshot("block","Chest","{}",stateJson,bytes,true),UUID.randomUUID(),new CarryLedger.Location("minecraft:overworld",1,70,2,0),CarryLedger.NONE);
  var persisted=new CarryLedger(store).get(id).snapshot();
  BlockState restoredState=BlockState.CODEC.parse(JsonOps.INSTANCE,com.google.gson.JsonParser.parseString(persisted.blockState())).getOrThrow().rotate(CarryWorld.rotation(1));
  BlockPos destination=new BlockPos(9,80,7);BlockEntity restored=CarryBlockSnapshot.decode(persisted.data(),destination,restoredState,provider);
  require(restored.getBlockPos().equals(destination),"block entity moved to authoritative destination");
  require(restored.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING)==Direction.SOUTH,"native state rotation");
  require(ItemStack.matches(((Container)restored).getItem(0),named),"named inventory preserved");
  require(ItemStack.matches(((Container)restored).getItem(9),box),"nested container components preserved");
  ItemStack visual=CarryDisplays.visual(box);require(visual.get(DataComponents.CONTAINER)==null,"visual strips private inventory");require(box.get(DataComponents.CONTAINER)!=null,"visual never mutates owner");
  byte[] bad=Arrays.copyOf(bytes,bytes.length+1);boolean rejected=false;try{CarryBlockSnapshot.decode(bad,destination,restoredState,provider);}catch(java.io.IOException expected){rejected=true;}require(rejected,"trailing snapshot bytes rejected");
  var barrel=new BarrelBlockEntity(BlockPos.ZERO,Blocks.BARREL.defaultBlockState());barrel.setItem(26,named.copy());
  var barrelRestored=CarryBlockSnapshot.decode(CarryBlockSnapshot.encode(barrel,provider),destination,Blocks.BARREL.defaultBlockState(),provider);
  require(ItemStack.matches(((Container)barrelRestored).getItem(26),named),"barrel contents preserved");
  var visualData=CarryDisplays.visualData(false,new CarryLedger.Snapshot("item","Box","{}","",new byte[0],true),box,null,provider);
  ItemStack displayItem=ItemStack.CODEC.parse(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),visualData.get("item")).getOrThrow();
  require(displayItem.getItem()==Items.SHULKER_BOX&&displayItem.get(DataComponents.CONTAINER)==null,"native display NBT contains no inventory");
  var blockData=CarryDisplays.visualData(true,new CarryLedger.Snapshot("block","Chest","{}","{}",bytes,true),ItemStack.EMPTY,restoredState,provider);
  var displayState=BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,blockData.get("block_state")).getOrThrow();require(displayState.equals(restoredState),"native display full state");
  var transform=com.mojang.math.Transformation.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,blockData.get("transformation")).getOrThrow();
  require(transform.translation().equals(new org.joml.Vector3f(-.5f,0,-.5f)),"block display pivots about centered base");
  // Generic Entity.load queries the world's client/server flag when setting glow.
  // This fixture supplies the server answer while leaving the actual native display
  // NBT loader, item decoder, state decoder and transformation setters intact.
  var itemDisplay=new net.minecraft.world.entity.Display.ItemDisplay(net.minecraft.world.entity.EntityType.ITEM_DISPLAY,null){@Override public boolean isCurrentlyGlowing(){return hasGlowingTag();}};
  CarryDisplays.configure(itemDisplay,new CarryLedger.Snapshot("item","Box","{}","",new byte[0],true),box,null,provider);
  var getItem=net.minecraft.world.entity.Display.ItemDisplay.class.getDeclaredMethod("getItemStack");getItem.setAccessible(true);
  ItemStack nativeItem=(ItemStack)getItem.invoke(itemDisplay);
  require(nativeItem.getItem()==Items.SHULKER_BOX&&nativeItem.get(DataComponents.CONTAINER)==null,"actual native item display load preserves visual and strips inventory");
  var blockDisplay=new net.minecraft.world.entity.Display.BlockDisplay(net.minecraft.world.entity.EntityType.BLOCK_DISPLAY,null){@Override public boolean isCurrentlyGlowing(){return hasGlowingTag();}};
  CarryDisplays.configure(blockDisplay,new CarryLedger.Snapshot("block","Chest","{}","{}",bytes,true),ItemStack.EMPTY,restoredState,provider);
  var getState=net.minecraft.world.entity.Display.BlockDisplay.class.getDeclaredMethod("getBlockState");getState.setAccessible(true);
  require(getState.invoke(blockDisplay).equals(restoredState),"actual native block display load retains state");
  System.out.println("CarryBlockSnapshotTest passed");
 }
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}

