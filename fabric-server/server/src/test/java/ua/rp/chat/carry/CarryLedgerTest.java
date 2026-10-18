package ua.rp.chat.carry;
import java.nio.file.*;
import java.util.*;
import java.io.*;

public final class CarryLedgerTest {
 public static void main(String[] args) throws Exception {
  Path file=Files.createTempDirectory("carry-test").resolve("state.bin");
  CarryLedger ledger=new CarryLedger(new CarryLedger.FileStore(file));
  UUID giver=UUID.randomUUID(), receiver=UUID.randomUUID();
  var location=new CarryLedger.Location("minecraft:overworld",1,64,3,0);
  byte[] contents={1,3,8,42};
  var snapshot=new CarryLedger.Snapshot("block","Chest","{}","{}",contents,true);
  int[] physical={1};
  var lift=new CarryLedger.Effect() { public void apply(){physical[0]--;} public void rollback(){physical[0]++;} };
  UUID id=ledger.introduce(snapshot,giver,location,lift);
  require(physical[0]==0 && ledger.held(giver).id().equals(id),"lift conserves owner");
  fails(()->ledger.claim(id,receiver,CarryLedger.NONE));
  var failPlacement=new CarryLedger.Effect(){public void apply() throws IOException {throw new IOException("blocked");} public void rollback(){}};
  fails(()->ledger.consume(id,giver,failPlacement));
  require(ledger.held(giver)!=null,"failed place retains ownership");
  ledger.transfer(id,giver,receiver);
  fails(()->ledger.consume(id,giver,CarryLedger.NONE));
  ledger.release(id,receiver,location,CarryLedger.NONE);
  require(ledger.get(id).owner()==null,"release is recoverable prop");
  CarryLedger loaded=new CarryLedger(new CarryLedger.FileStore(file));
  require(Arrays.equals(contents,loaded.get(id).snapshot().data()),"container bytes restored");
  loaded.claim(id,giver,CarryLedger.NONE); loaded.recoverAll();
  require(loaded.get(id).owner()==null,"restart recovers held");
  CarryLedger failing=new CarryLedger(new CarryLedger.Store(){public List<CarryLedger.Entry> load(){return List.of();} public void save(Collection<CarryLedger.Entry> entries) throws IOException{throw new IOException("disk full");}});
  physical[0]=1; fails(()->failing.introduce(snapshot,giver,location,lift));
  require(physical[0]==1 && failing.entries().isEmpty(),"failed durable write restores source");
  var voxel=new CarryLedger.Snapshot("voxel","Sculpture","{}","",new byte[8192],true);
  UUID vid=loaded.introduce(voxel,giver,location,CarryLedger.NONE);
  require(new CarryLedger(new CarryLedger.FileStore(file)).get(vid).snapshot().data().length==8192,"voxel bytes restored");
  var offers=new CarryOffers();
  UUID offer=offers.offer(giver,receiver,id.toString(),100);
  fails(()->offers.accept(offer,giver,id.toString(),101));
  fails(()->offers.accept(offer,receiver,"stale",101));
  require(offers.accept(offer,receiver,id.toString(),101).from().equals(giver),"only recipient accepts current identity");
  fails(()->offers.accept(offer,receiver,id.toString(),101));
  UUID expired=offers.offer(giver,receiver,"item",100); offers.expire(30101);
  fails(()->offers.accept(expired,receiver,"item",30101));
  UUID cancelled=offers.offer(giver,receiver,"item",100);offers.cancel(giver);
  fails(()->offers.accept(cancelled,receiver,"item",101));
  int[] hand={0};boolean[] reject={false};
  var small=new CarryLedger.Snapshot("item","Cup","{}","",new byte[0],false);
  var collectible=new CarryLedger(new CarryLedger.Store(){public List<CarryLedger.Entry> load(){return List.of();} public void save(Collection<CarryLedger.Entry> entries)throws IOException{if(reject[0]&&entries.isEmpty())throw new IOException("disk full");}});
  UUID cup=collectible.introduce(small,null,location,CarryLedger.NONE);
  var toHand=new CarryLedger.Effect(){public void apply(){hand[0]++;}public void rollback(){hand[0]--;}};
  // Fail the durable removal after a claim could have succeeded: picking up a
  // scene prop must be one commit, not an intermediate held-owner transaction.
  reject[0]=true;fails(()->collectible.collect(cup,giver,toHand));
  require(hand[0]==0&&collectible.get(cup)!=null&&collectible.get(cup).owner()==null,
    "failed scene pickup must retain the unowned prop and empty hand");
  reject[0]=false;collectible.collect(cup,giver,toHand);
  require(hand[0]==1&&collectible.get(cup)==null,"scene prop has exactly one hand owner");
  fails(()->collectible.collect(cup,receiver,toHand));require(hand[0]==1,"stale second pickup cannot duplicate the prop");
  System.out.println("CarryLedgerTest passed");
 }
 interface Throwing {void run()throws Exception;}
 static void fails(Throwing r)throws Exception{try{r.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Expected rejection");}
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
