package ua.rp.chat.carry;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import ua.rp.chat.RPChat;
import ua.rp.chat.mixin.ItemEntityAccessor;

/** Server-thread authority for physical carrying. Clients provide hints, never stacks/destinations. */
public final class CarryManager {
 private static final double RANGE=CarryRules.REACH;
 private final RPChat plugin;
 private CarryLedger ledger;private CarryWorld world;private CarryDisplays displays;
 private final Map<UUID,Palm> palms=new HashMap<>();
 private final Map<UUID,Long> rate=new HashMap<>();
 private final CarryOffers offers=new CarryOffers();
 private long ticks,revision;
 private record Palm(int slot,ItemStack identity,UUID token,long shownUntil){}
 public CarryManager(RPChat plugin){this.plugin=plugin;}
 public void register(){
  PayloadTypeRegistry.serverboundPlay().register(CarryActionPayload.TYPE,CarryActionPayload.CODEC);
  PayloadTypeRegistry.clientboundPlay().register(CarrySyncPayload.TYPE,CarrySyncPayload.CODEC);
  ServerPlayNetworking.registerGlobalReceiver(CarryActionPayload.TYPE,(payload,ctx)->ctx.server().execute(()->handle(ctx.player(),payload.json())));
  ServerEntityEvents.ENTITY_LOAD.register((entity,level)->{if(displays!=null)displays.loaded(entity);});
  ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(entity instanceof ServerPlayer player)recover(player);});
 }
 public void start(){
  try{world=new CarryWorld(plugin);ledger=new CarryLedger(new CarryLedger.FileStore(plugin.getServer().getWorldPath(LevelResource.ROOT).resolve("rpchat-carry.bin")));ledger.recoverAll();displays=new CarryDisplays(plugin,world);for(ServerLevel level:plugin.getServer().getAllLevels())for(Entity entity:level.getAllEntities())displays.loaded(entity);}
  catch(Exception failure){ledger=null;plugin.getLogger().severe("Physical carry disabled: "+failure);}
 }
 public boolean busy(ServerPlayer player){return ledger!=null&&ledger.held(player.getUUID())!=null;}
 public boolean supported(ServerPlayer player){return ServerPlayNetworking.canSend(player,CarrySyncPayload.TYPE);}
 public void takeLoose(ServerPlayer player,UUID target){JsonObject action=new JsonObject();action.addProperty("action","take");action.addProperty("target",target.toString());handle(player,action.toString());}
 public void handle(ServerPlayer player,String input){
  if(ledger==null)return;if(!valid(player)){deny(player,"Сейчас нельзя действовать с предметами.");return;}
  long now=System.currentTimeMillis();Long prior=rate.put(player.getUUID(),now);if(prior!=null&&now-prior<75)return;
  try{
   JsonElement parsed=JsonParser.parseString(input);if(!parsed.isJsonObject())throw new IllegalArgumentException("Expected carry action");JsonObject json=parsed.getAsJsonObject();String action=json.get("action").getAsString();
   switch(action){
    case "sync"->{}
    case "take"->take(player,json);
    case "hold"->hold(player);
    case "place"->place(player,json,false);
    case "drop"->place(player,json,true);
    case "stow"->stow(player);
    case "show"->show(player);
    case "offer"->offer(player,json);
    case "accept"->accept(player,uuid(json,"offer"));
    case "decline"->offers.decline(uuid(json,"offer"),player.getUUID());
    default->throw new IllegalArgumentException("Unknown carry action");
   }
  }catch(Exception failure){deny(player,failure.getMessage()==null?"Carry request rejected":failure.getMessage());}
  revision++;syncAll();
 }
 private boolean valid(ServerPlayer player){return player!=null&&plugin.getServer().getPlayerList().getPlayer(player.getUUID())==player&&CarryRules.actor(!plugin.getAuthManager().isPendingAuth(player.getUUID()),player.isAlive(),player.isSpectator(),plugin.getAcquaintanceManager().isBound(player),plugin.getStaminaManager().isUnconscious(player));}
 private void empty(ServerPlayer player,boolean both){
  if(busy(player)||!player.getMainHandItem().isEmpty()||(both&&!player.getOffhandItem().isEmpty()))throw new IllegalStateException("Освободите руки.");
 }
 private Palm palm(ServerPlayer player){Palm palm=palms.get(player.getUUID());if(palm==null)return null;
  if(palm.slot()!=player.getInventory().getSelectedSlot()||!ItemStack.matches(palm.identity(),player.getMainHandItem())){palms.remove(player.getUUID());offers.cancel(player.getUUID());return null;}return palm;
 }
 private String identity(ServerPlayer player){CarryLedger.Entry held=ledger.held(player.getUUID());if(held!=null)return held.id().toString();Palm palm=palm(player);return palm==null?"":palm.token().toString();}
 private static UUID uuid(JsonObject object,String field){return UUID.fromString(object.get(field).getAsString());}
 private BlockHitResult ray(ServerPlayer player){
  Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(RANGE));ServerLevel level=(ServerLevel)player.level();
  int minX=((int)Math.floor(Math.min(eye.x,end.x)))>>4,maxX=((int)Math.floor(Math.max(eye.x,end.x)))>>4;
  int minZ=((int)Math.floor(Math.min(eye.z,end.z)))>>4,maxZ=((int)Math.floor(Math.max(eye.z,end.z)))>>4;
  for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(level.getChunkSource().getChunkNow(x,z)==null)throw new IllegalStateException("Цель находится в незагруженной области.");
  return level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
 }
 private void take(ServerPlayer player,JsonObject json)throws IOException {
  empty(player,false);ServerLevel level=(ServerLevel)player.level();Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(RANGE));BlockHitResult block=ray(player);
  double closest=block.getType()==HitResult.Type.MISS?RANGE*RANGE:eye.distanceToSqr(block.getLocation());
  UUID target=null;ItemEntity loose=null;CarryLedger.Entry prop=null;
  for(Entity entity:level.getEntities(player,player.getBoundingBox().expandTowards(player.getLookAngle().scale(RANGE)).inflate(.3),e->e instanceof ItemEntity&&e.isAlive())){
   var hit=entity.getBoundingBox().inflate(.14).clip(eye,end);if(hit.isPresent()&&eye.distanceToSqr(hit.get())<closest){closest=eye.distanceToSqr(hit.get());loose=(ItemEntity)entity;prop=null;target=entity.getUUID();}
  }
  for(CarryLedger.Entry entry:ledger.entries()){
   CarryLedger.Location l=entry.location();if(entry.owner()!=null||!l.world().equals(level.dimension().identifier().toString()))continue;
   double size=entry.snapshot().large()?1:.36;AABB bounds=new AABB(l.x()-size/2,l.y(),l.z()-size/2,l.x()+size/2,l.y()+size,l.z()+size/2);
   var hit=bounds.clip(eye,end);if(hit.isPresent()&&eye.distanceToSqr(hit.get())<closest){closest=eye.distanceToSqr(hit.get());prop=entry;loose=null;target=entry.id();}
  }
  if(json.has("target")&&!uuid(json,"target").equals(target))throw new IllegalStateException("Наведитесь на предмет точнее.");
  if(prop!=null){claim(player,prop);return;}if(loose!=null){takeItem(player,loose);return;}
  if(!player.isShiftKeyDown()||block.getType()!=HitResult.Type.BLOCK)throw new IllegalStateException("Shift+ПКМ: поднять предмет обстановки.");
  BlockPos pos=block.getBlockPos();if(json.has("block")){JsonArray hint=json.getAsJsonArray("block");if(hint.size()!=3||hint.get(0).getAsInt()!=pos.getX()||hint.get(1).getAsInt()!=pos.getY()||hint.get(2).getAsInt()!=pos.getZ())throw new IllegalStateException("Цель изменилась.");}
  empty(player,true);CarryLedger.Snapshot snapshot=world.snapshot(player,pos);
  ledger.introduce(snapshot,player.getUUID(),location(level,pos.getX()+.5,pos.getY(),pos.getZ()+.5,0),world.removalEffect(level,pos,snapshot));offers.cancel(player.getUUID());
 }
 private void takeItem(ServerPlayer player,ItemEntity entity)throws IOException {
  UUID recipient=((ItemEntityAccessor)(Object)entity).eclipse$getPickupTarget();
  if(entity.hasPickUpDelay()||(recipient!=null&&!recipient.equals(player.getUUID())))throw new IllegalStateException("Предмет пока недоступен.");
  ItemStack original=entity.getItem().copy();if(original.isEmpty())throw new IllegalStateException("Предмет недоступен.");
  ServerLevel level=(ServerLevel)player.level();CarryLedger.Snapshot snapshot=world.itemSnapshot(original,level);
  if(snapshot.large()){
   empty(player,true);ledger.introduce(snapshot,player.getUUID(),initialRecovery(player,snapshot),new CarryLedger.Effect(){public void apply(){entity.setItem(original.copyWithCount(original.getCount()-1));}public void rollback(){entity.setItem(original.copy());}});
  }else {empty(player,false);var split=CarryInventory.takeOne(original,player.getMainHandItem());player.setItemInHand(InteractionHand.MAIN_HAND,split.hand());entity.setItem(split.remainder());markPalm(player);}
  if(entity.getItem().isEmpty())entity.discard();player.containerMenu.broadcastChanges();
 }
 private void claim(ServerPlayer player,CarryLedger.Entry prop)throws IOException {
  empty(player,prop.snapshot().large());
  if(prop.snapshot().large())ledger.claim(prop.id(),player.getUUID(),CarryLedger.NONE);
  else {ItemStack item=world.decode(prop.snapshot().item(),(ServerLevel)player.level());
   ledger.collect(prop.id(),player.getUUID(),new CarryLedger.Effect(){public void apply(){player.setItemInHand(InteractionHand.MAIN_HAND,item);}public void rollback(){player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);}});markPalm(player);}
 }
 private void hold(ServerPlayer player)throws IOException {
  if(busy(player))throw new IllegalStateException("Руки заняты.");ItemStack held=player.getMainHandItem();if(held.isEmpty())throw new IllegalStateException("Выберите предмет в руке.");
  if(world.large(held)){
   if(!player.getOffhandItem().isEmpty())throw new IllegalStateException("Нужны две свободные руки.");
   ItemStack original=held.copy();CarryInventory.Plan remainder=original.getCount()>1?CarryInventory.stow(player.getInventory(),player.getInventory().getSelectedSlot(),original.copyWithCount(original.getCount()-1)):null;
   CarryLedger.Snapshot snapshot=world.itemSnapshot(original,(ServerLevel)player.level());
   ledger.introduce(snapshot,player.getUUID(),initialRecovery(player,snapshot),new CarryLedger.Effect(){public void apply(){if(remainder!=null)remainder.apply();player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);}public void rollback(){if(remainder!=null)remainder.rollback();player.setItemInHand(InteractionHand.MAIN_HAND,original);}});palms.remove(player.getUUID());
  }else {if(held.getCount()>1){ItemStack original=held.copy();CarryInventory.Plan remainder=CarryInventory.stow(player.getInventory(),player.getInventory().getSelectedSlot(),original.copyWithCount(original.getCount()-1));remainder.apply();player.setItemInHand(InteractionHand.MAIN_HAND,original.copyWithCount(1));}markPalm(player);}offers.cancel(player.getUUID());
 }
 private void markPalm(ServerPlayer player){palms.put(player.getUUID(),new Palm(player.getInventory().getSelectedSlot(),player.getMainHandItem().copy(),UUID.randomUUID(),0));player.containerMenu.broadcastChanges();}
 private void place(ServerPlayer player,JsonObject json,boolean drop)throws IOException {
  CarryLedger.Entry held=ledger.held(player.getUUID());Palm palm=palm(player);if(held==null&&palm==null)throw new IllegalStateException("В руках ничего нет.");
  float yaw=json.has("yaw")?json.get("yaw").getAsFloat():0;int turns=CarryRules.turns(yaw);
  ServerLevel level=(ServerLevel)player.level();CarryLedger.Snapshot snapshot=held==null?world.itemSnapshot(player.getMainHandItem(),level):held.snapshot();
  BlockHitResult hit=ray(player);BlockPos pos;Vec3 base;
  if(drop){
   base=dropSpot(player,snapshot);pos=BlockPos.containing(base);
  }else {
   if(hit.getType()!=HitResult.Type.BLOCK||hit.getDirection()!=Direction.UP)throw new IllegalStateException("Наведитесь на верхнюю сторону опоры.");
   base=snapshot.kind().equals("item")?hit.getLocation().add(0,.006,0):Vec3.atBottomCenterOf(hit.getBlockPos().above());pos=BlockPos.containing(base);
  }
  if(!world.permitted(player,pos)||!level.getFluidState(pos).isEmpty())throw new IllegalStateException("Место занято или защищено.");
  if(!snapshot.kind().equals("item")&&!level.getBlockState(pos).isAir())throw new IllegalStateException("Место занято.");
  double size=snapshot.large()?1:.36;AABB bounds=new AABB(base.x-size/2,base.y,base.z-size/2,base.x+size/2,base.y+size,base.z+size/2);
  if(!level.noCollision(bounds)||!level.getEntities(player,bounds,e->e.isAlive()&&!(e instanceof net.minecraft.world.entity.Display)).isEmpty()||player.getBoundingBox().intersects(bounds)||overlapsProp(level,bounds))throw new IllegalStateException("Недостаточно места.");
  if(held!=null&&!snapshot.kind().equals("item")&&!drop){
   if(!world.canRestore(player,pos,snapshot,turns))throw new IllegalStateException("Здесь объект поставить нельзя.");
   ledger.consume(held.id(),player.getUUID(),world.placementEffect(level,pos,snapshot,turns));
  }else {
   CarryLedger.Location destination=location(level,base.x,base.y,base.z,turns*90);
   if(held!=null)ledger.release(held.id(),player.getUUID(),destination,CarryLedger.NONE);
   else {ItemStack original=player.getMainHandItem().copy();ledger.introduce(snapshot,null,destination,new CarryLedger.Effect(){public void apply(){player.getMainHandItem().shrink(1);}public void rollback(){player.setItemInHand(InteractionHand.MAIN_HAND,original);}});palms.remove(player.getUUID());}
  }
  offers.cancel(player.getUUID());player.containerMenu.broadcastChanges();
 }
 private Vec3 dropSpot(ServerPlayer player,CarryLedger.Snapshot snapshot){
  ServerLevel level=(ServerLevel)player.level();double size=snapshot.large()?1:.36;
  return CarryRecovery.find(player.position(),player.getYRot(),base->{
   BlockPos pos=BlockPos.containing(base);if(base.distanceToSqr(player.position())>RANGE*RANGE||!world.permitted(player,pos)||level.getBlockState(pos.below()).getCollisionShape(level,pos.below()).isEmpty()||!level.getFluidState(pos).isEmpty())return false;
   AABB box=new AABB(base.x-size/2,base.y,base.z-size/2,base.x+size/2,base.y+size,base.z+size/2);
   if(!level.noCollision(box)||player.getBoundingBox().intersects(box)||!level.getEntities(player,box,e->e.isAlive()&&!(e instanceof net.minecraft.world.entity.Display)).isEmpty()||overlapsProp(level,box))return false;
   BlockHitResult obstruction=level.clip(new ClipContext(player.getEyePosition(),base.add(0,size*.5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
   return obstruction.getType()==HitResult.Type.MISS;
  });
 }
 private boolean overlapsProp(ServerLevel level,AABB bounds){
  for(CarryLedger.Entry e:ledger.entries())if(e.owner()==null&&e.location().world().equals(level.dimension().identifier().toString())){
   var p=e.location();double size=e.snapshot().large()?1:.36;if(bounds.intersects(new AABB(p.x()-size/2,p.y(),p.z()-size/2,p.x()+size/2,p.y()+size,p.z()+size/2)))return true;
  }return false;
 }
 private void stow(ServerPlayer player)throws IOException {
  CarryLedger.Entry held=ledger.held(player.getUUID());
  if(held!=null){if(!held.snapshot().kind().equals("item"))throw new IllegalStateException("Объект мира нельзя убрать в инвентарь.");
   ItemStack item=world.decode(held.snapshot().item(),(ServerLevel)player.level());CarryInventory.Plan plan=CarryInventory.stow(player.getInventory(),-1,item);ledger.consume(held.id(),player.getUUID(),plan);
  }else {Palm palm=palm(player);if(palm==null)throw new IllegalStateException("В руках ничего нет.");ItemStack moving=player.getMainHandItem().copy();CarryInventory.Plan plan=CarryInventory.stow(player.getInventory(),palm.slot(),moving);plan.apply();player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);palms.remove(player.getUUID());}
  offers.cancel(player.getUUID());player.containerMenu.broadcastChanges();
 }
 private void show(ServerPlayer player){Palm palm=palm(player);if(palm==null)throw new IllegalStateException("Нужен предмет в ладони.");palms.put(player.getUUID(),new Palm(palm.slot(),palm.identity(),palm.token(),System.currentTimeMillis()+3000));}
 private void offer(ServerPlayer player,JsonObject json){
  String identity=identity(player);if(identity.isEmpty())throw new IllegalStateException("В руках ничего нет.");ServerPlayer recipient=null;
  if(json.has("target"))recipient=plugin.getServer().getPlayerList().getPlayer(uuid(json,"target"));
  else {double best=RANGE*RANGE;for(ServerPlayer candidate:plugin.getServer().getPlayerList().getPlayers())if(candidate!=player&&candidate.level()==player.level()&&player.distanceToSqr(candidate)<best&&player.hasLineOfSight(candidate)&&player.getLookAngle().dot(candidate.getEyePosition().subtract(player.getEyePosition()).normalize())>.85){recipient=candidate;best=player.distanceToSqr(candidate);}}
  nearby(player,recipient);offers.offer(player.getUUID(),recipient.getUUID(),identity,System.currentTimeMillis());
 }
 private void nearby(ServerPlayer from,ServerPlayer to){if(!valid(from)||!valid(to)||from==to||!CarryRules.nearby(from.level()==to.level(),from.distanceToSqr(to),from.hasLineOfSight(to),to.hasLineOfSight(from)))throw new IllegalStateException("Игрок должен быть рядом и виден.");}
 private void accept(ServerPlayer recipient,UUID id)throws IOException {
  CarryOffers.Offer offer=offers.get(id);if(offer==null||!offer.to().equals(recipient.getUUID()))throw new IllegalStateException("Предложение недоступно.");
  ServerPlayer giver=plugin.getServer().getPlayerList().getPlayer(offer.from());nearby(giver,recipient);
  long acceptedAt=System.currentTimeMillis();String identity=identity(giver);if(!identity.equals(offer.identity())||offer.expires()<=acceptedAt)throw new IllegalStateException("Предмет изменился или время истекло.");
  CarryLedger.Entry held=ledger.held(giver.getUUID());empty(recipient,held!=null&&held.snapshot().large());
  if(held!=null)ledger.transfer(held.id(),giver.getUUID(),recipient.getUUID());
  else {Palm palm=palm(giver);if(palm==null)throw new IllegalStateException("Предмет изменился.");var transfer=CarryInventory.transferOne(palm.identity(),giver.getMainHandItem(),recipient.getMainHandItem());giver.setItemInHand(InteractionHand.MAIN_HAND,transfer.remainder());recipient.setItemInHand(InteractionHand.MAIN_HAND,transfer.hand());palms.remove(giver.getUUID());markPalm(recipient);}
  offers.accept(id,recipient.getUUID(),identity,acceptedAt);offers.cancel(giver.getUUID());giver.containerMenu.broadcastChanges();recipient.containerMenu.broadcastChanges();
 }
 public void recover(ServerPlayer player){
  if(ledger==null)return;CarryLedger.Entry held=ledger.held(player.getUUID());
  if(held!=null)try{
   CarryLedger.Location recovery=held.location();
   if(recovery.world().equals(player.level().dimension().identifier().toString()))try{
    Vec3 safe=dropSpot(player,held.snapshot());recovery=location((ServerLevel)player.level(),safe.x,safe.y,safe.z,player.getYRot());
   }catch(IllegalStateException unavailable){/* Keep the last persisted safe location. */}
   ledger.release(held.id(),player.getUUID(),recovery,CarryLedger.NONE);
  }catch(IOException failure){plugin.getLogger().severe("Carry recovery remains persisted for "+player.getUUID()+": "+failure);}
  palms.remove(player.getUUID());offers.cancel(player.getUUID());rate.remove(player.getUUID());revision++;syncAll();
 }
 public void tick(){
  if(ledger==null)return;ticks++;offers.expire(System.currentTimeMillis());
  for(ServerPlayer player:plugin.getServer().getPlayerList().getPlayers()){
   palm(player);CarryLedger.Entry held=ledger.held(player.getUUID());if(held==null)continue;
   if(!valid(player)||!held.location().world().equals(player.level().dimension().identifier().toString())){recover(player);continue;}
   // Inventory changes from commands/other mods never grant unrelated use while busy.
   if(ticks%100==0)try{
    Vec3 safe=dropSpot(player,held.snapshot());ledger.relocateHeld(player.getUUID(),location((ServerLevel)player.level(),safe.x,safe.y,safe.z,player.getYRot()));
   }catch(IOException failure){deny(player,"Не удалось сохранить положение объекта.");}
    catch(IllegalStateException unavailable){/* Never replace a safe recovery point with a wall intersection. */}
  }
  displays.tick(ledger);if(ticks%10==0){revision++;syncAll();}
 }
 public void shutdown(){if(ledger==null)return;for(ServerPlayer player:plugin.getServer().getPlayerList().getPlayers())recover(player);displays.clear();}
 private CarryLedger.Location initialRecovery(ServerPlayer player,CarryLedger.Snapshot snapshot){Vec3 base=dropSpot(player,snapshot);return location((ServerLevel)player.level(),base.x,base.y,base.z,player.getYRot());}
 private static CarryLedger.Location location(ServerLevel level,double x,double y,double z,float yaw){return new CarryLedger.Location(level.dimension().identifier().toString(),x,y,z,yaw);}
 private void syncAll(){if(ledger==null)return;if(displays!=null)displays.tick(ledger);for(ServerPlayer player:plugin.getServer().getPlayerList().getPlayers())if(supported(player)&&valid(player))sync(player);}
 private void sync(ServerPlayer viewer){
  CarrySyncBuilder snapshot=new CarrySyncBuilder(revision,world.liftable());
  List<ServerPlayer> visible=new ArrayList<>(plugin.getServer().getPlayerList().getPlayers());
  visible.removeIf(player->!valid(player)||player.level()!=viewer.level()||player.distanceToSqr(viewer)>4096);
  visible.sort(Comparator.comparingDouble(player->player==viewer?-1:player.distanceToSqr(viewer)));
  for(ServerPlayer player:visible){
   CarryLedger.Entry held=ledger.held(player.getUUID());Palm palm=palm(player);if(held==null&&palm==null)continue;
   JsonObject record=new JsonObject();record.addProperty("player",player.getUUID().toString());record.addProperty("slot",player.getInventory().getSelectedSlot());record.addProperty("shownUntil",palm==null?0:palm.shownUntil());
   if(held!=null){
    record.addProperty("object",held.id().toString());record.addProperty("entity",displays.entity(held.id()));
    details(record,held.snapshot(),(ServerLevel)player.level(),player==viewer);
   }else {
    record.addProperty("kind","item");record.addProperty("stowable",true);record.addProperty("mode","palm");
    record.addProperty("name",CarrySyncBuilder.name(player.getMainHandItem().getHoverName().getString()));
    if(player==viewer)CarrySyncBuilder.visual(record,world.encode(CarryDisplays.visual(player.getMainHandItem().copyWithCount(1)),(ServerLevel)player.level()));
   }
   snapshot.player(record);
  }
  List<CarryLedger.Entry> nearbyProps=new ArrayList<>(ledger.entries());
  nearbyProps.removeIf(entry->entry.owner()!=null||!entry.location().world().equals(viewer.level().dimension().identifier().toString())||propDistance(viewer,entry)>4096);
  nearbyProps.sort(Comparator.comparingDouble(entry->propDistance(viewer,entry)));
  for(CarryLedger.Entry entry:nearbyProps){
   CarryLedger.Location l=entry.location();JsonObject record=new JsonObject();
   record.addProperty("id",entry.id().toString());record.addProperty("entity",displays.entity(entry.id()));record.addProperty("world",l.world());
   record.addProperty("x",l.x());record.addProperty("y",l.y());record.addProperty("z",l.z());record.addProperty("yaw",l.yaw());
   details(record,entry.snapshot(),(ServerLevel)viewer.level(),false);
   if(!snapshot.prop(record))break;
  }
  for(CarryOffers.Offer offer:offers.entries())if(offer.from().equals(viewer.getUUID())||offer.to().equals(viewer.getUUID())){
   JsonObject record=new JsonObject();record.addProperty("id",offer.id().toString());record.addProperty("from",offer.from().toString());record.addProperty("to",offer.to().toString());record.addProperty("expires",offer.expires());
   ServerPlayer from=plugin.getServer().getPlayerList().getPlayer(offer.from());CarryLedger.Entry held=from==null?null:ledger.held(from.getUUID());
   record.addProperty("name",CarrySyncBuilder.name(held==null?(from==null?"Предмет":from.getMainHandItem().getHoverName().getString()):held.snapshot().name()));
   if(!snapshot.offer(record))break;
  }
  ServerPlayNetworking.send(viewer,new CarrySyncPayload(snapshot.json()));
 }
 private static double propDistance(ServerPlayer viewer,CarryLedger.Entry entry){var l=entry.location();return viewer.position().distanceToSqr(new Vec3(l.x(),l.y(),l.z()));}
 private void details(JsonObject record,CarryLedger.Snapshot snapshot,ServerLevel level,boolean visual){
  record.addProperty("kind",snapshot.kind());record.addProperty("stowable",snapshot.kind().equals("item"));
  record.addProperty("mode",snapshot.large()?"two_hands":"palm");record.addProperty("name",CarrySyncBuilder.name(snapshot.name()));
  if(visual){
   CarrySyncBuilder.visual(record,world.encode(CarryDisplays.visual(world.decode(snapshot.item(),level)),level));
   if(!snapshot.blockState().isEmpty())record.add("blockState",JsonParser.parseString(snapshot.blockState()));
  }
 }
 private static void deny(ServerPlayer player,String message){player.sendSystemMessage(Component.literal(message),true);}
}
