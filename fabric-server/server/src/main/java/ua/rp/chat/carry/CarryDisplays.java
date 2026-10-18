package ua.rp.chat.carry;

import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;

/** Disposable native visual mirrors. They never contain transferable inventory or drop items. */
final class CarryDisplays {
 static final String TAG="rpchat_carry_visual";
 private final RPChat plugin;private final CarryWorld world;
 private final Map<UUID,Entity> mirrors=new HashMap<>();
 private final List<Entity> loaded=new ArrayList<>();
 CarryDisplays(RPChat plugin,CarryWorld world){this.plugin=plugin;this.world=world;}
 void loaded(Entity entity){if(entity.entityTags().contains(TAG))loaded.add(entity);}
 void tick(CarryLedger ledger){
  for(Entity entity:loaded)if(!mirrors.containsValue(entity))entity.discard();loaded.clear();
  Set<UUID> live=new HashSet<>();for(CarryLedger.Entry entry:ledger.entries()){
   ServerPlayer owner=entry.owner()==null?null:plugin.getServer().getPlayerList().getPlayer(entry.owner());
   ServerLevel level=owner==null?level(entry.location().world()):(ServerLevel)owner.level();if(level==null)continue;
   Vec3 base=owner==null?new Vec3(entry.location().x(),entry.location().y(),entry.location().z()):anchor(owner);
   if(level.getChunkSource().getChunkNow(((int)Math.floor(base.x))>>4,((int)Math.floor(base.z))>>4)==null)continue;
   live.add(entry.id());Entity entity=mirrors.get(entry.id());
   if(entity==null||!entity.isAlive()||entity.level()!=level){if(entity!=null)entity.discard();entity=create(entry,level,base);mirrors.put(entry.id(),entity);}
   boolean block=entity instanceof Display.BlockDisplay;entity.setPos(base.x,base.y+(block?0:(entry.snapshot().large()?.5:.18)),base.z);
   entity.setYRot(owner==null?entry.location().yaw():owner.getYRot());
  }
  mirrors.entrySet().removeIf(e->{if(!live.contains(e.getKey())){e.getValue().discard();return true;}return false;});
 }
 int entity(UUID id){Entity e=mirrors.get(id);return e==null?-1:e.getId();}
 private Entity create(CarryLedger.Entry entry,ServerLevel level,Vec3 base){
  Display display;CarryLedger.Snapshot snapshot=entry.snapshot();BlockState state=snapshot.blockState().isEmpty()?null:world.state(snapshot.blockState());
  // 26.1's native BlockModelResolver includes state-aware special models for
  // chests and shulkers as well as ordinary baked block meshes.
  if(state!=null){Display.BlockDisplay d=new Display.BlockDisplay(EntityType.BLOCK_DISPLAY,level){@Override public boolean shouldBeSaved(){return false;}};display=d;}
  else {Display.ItemDisplay d=new Display.ItemDisplay(EntityType.ITEM_DISPLAY,level){@Override public boolean shouldBeSaved(){return false;}};display=d;}
  configure(display,snapshot,world.decode(snapshot.item(),level),state,level.registryAccess());
  display.setPos(base.x,base.y+(display instanceof Display.BlockDisplay?0:(snapshot.large()?.5:.18)),base.z);
  display.addTag(TAG);display.setNoGravity(true);display.setInvulnerable(true);level.addFreshEntity(display);return display;
 }
 static void configure(Display display,CarryLedger.Snapshot snapshot,ItemStack stack,BlockState state,net.minecraft.core.HolderLookup.Provider registries){
  display.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,registries,visualData(display instanceof Display.BlockDisplay,snapshot,stack,state,registries)));
 }
 static net.minecraft.nbt.CompoundTag visualData(boolean block,CarryLedger.Snapshot snapshot,ItemStack stack,BlockState state,net.minecraft.core.HolderLookup.Provider registries){
  net.minecraft.nbt.CompoundTag data=new net.minecraft.nbt.CompoundTag();
  float scale=snapshot.large()?1:.36f;
  var transform=new com.mojang.math.Transformation(block?new org.joml.Vector3f(-.5f,0,-.5f):new org.joml.Vector3f(),new org.joml.Quaternionf(),new org.joml.Vector3f(scale),new org.joml.Quaternionf());
  data.put("transformation",com.mojang.math.Transformation.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,transform).getOrThrow());
  data.putFloat("width",1);data.putFloat("height",1);data.putFloat("view_range",1);data.putInt("teleport_duration",2);
  if(block)data.put("block_state",net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,state).getOrThrow());
  else {data.put("item",ItemStack.CODEC.encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),visual(stack)).getOrThrow());data.putString("item_display","none");}
  return data;
 }

 static ItemStack visual(ItemStack source){ItemStack visual=source.copy();visual.remove(DataComponents.CONTAINER);visual.remove(DataComponents.BLOCK_ENTITY_DATA);visual.remove(DataComponents.CONTAINER_LOOT);return visual;}
 static Vec3 anchor(ServerPlayer player){var point=CarryTransforms.anchor(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.isCrouching());return new Vec3(point.x(),point.y(),point.z());}
 ServerLevel level(String id){for(ServerLevel level:plugin.getServer().getAllLevels())if(level.dimension().identifier().toString().equals(id))return level;return null;}
 void clear(){for(Entity e:mirrors.values())e.discard();mirrors.clear();}
}
