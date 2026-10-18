package ua.rp.chat.carry;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Authoritative object ownership. Native displays are projections of these records only. */
public final class CarryLedger {
 public record Snapshot(String kind,String name,String item,String blockState,byte[] data,boolean large) {
  public Snapshot { Objects.requireNonNull(kind); Objects.requireNonNull(name); Objects.requireNonNull(item); Objects.requireNonNull(blockState); data=data.clone(); if(data.length>2_097_152)throw new IllegalArgumentException("Snapshot too large"); }
  @Override public byte[] data(){return data.clone();}
 }
 public record Location(String world,double x,double y,double z,float yaw) {
  public Location { if(world==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw))throw new IllegalArgumentException("Invalid location"); }
 }
 public record Entry(UUID id,Snapshot snapshot,UUID owner,Location location) {}
    public interface Effect {
        void apply() throws IOException;
        void rollback() throws IOException;

        /** Runs after file replacement and in-memory ownership commit. Never rolled back. */
        default void committed() { }
    }
 public static final Effect NONE=new Effect(){public void apply(){}public void rollback(){}};
 public interface Store {List<Entry> load()throws IOException;void save(Collection<Entry> entries)throws IOException;}
 private final Store store;
 private Map<UUID,Entry> objects=new LinkedHashMap<>();
 private long revision;
 public CarryLedger(Store store)throws IOException {
  this.store=store; Set<UUID> owners=new HashSet<>();
  for(Entry e:store.load()){
   if(objects.put(e.id(),e)!=null || (e.owner()!=null&&!owners.add(e.owner()))) throw new IOException("Duplicate carry ownership");
  }
 }
 public long revision(){return revision;}
 public List<Entry> entries(){return List.copyOf(objects.values());}
 public Entry get(UUID id){return objects.get(id);}
 public Entry held(UUID owner){return objects.values().stream().filter(e->owner.equals(e.owner())).findFirst().orElse(null);}
 public UUID introduce(Snapshot snapshot,UUID owner,Location location,Effect effect)throws IOException {
  if(objects.size()>=4096)throw new IllegalStateException("Carry object limit reached");
  free(owner); UUID id=UUID.randomUUID(); Map<UUID,Entry> next=new LinkedHashMap<>(objects);
  next.put(id,new Entry(id,snapshot,owner,location));commit(next,effect);return id;
 }
 public void claim(UUID id,UUID owner,Effect effect)throws IOException {
  Entry e=required(id);if(e.owner()!=null)throw new IllegalStateException("Object already held");free(owner);
  replace(new Entry(id,e.snapshot(),owner,e.location()),effect);
 }
 public void release(UUID id,UUID owner,Location location,Effect effect)throws IOException {
  Entry e=owned(id,owner);replace(new Entry(id,e.snapshot(),null,location),effect);
 }
 public void transfer(UUID id,UUID from,UUID to)throws IOException {
  Entry e=owned(id,from);free(to);replace(new Entry(id,e.snapshot(),to,e.location()),NONE);
 }
 public void consume(UUID id,UUID owner,Effect effect)throws IOException {
  owned(id,owner);Map<UUID,Entry> next=new LinkedHashMap<>(objects);next.remove(id);commit(next,effect);
 }
 public void collect(UUID id,UUID owner,Effect effect)throws IOException {
  if(owner==null)throw new IllegalArgumentException("Missing collector");
  free(owner);Entry entry=required(id);if(entry.owner()!=null)throw new IllegalStateException("Object already held");
  Map<UUID,Entry> next=new LinkedHashMap<>(objects);next.remove(id);commit(next,effect);
 }
 public void relocateHeld(UUID owner,Location location)throws IOException {
  Entry e=held(owner);if(e!=null)replace(new Entry(e.id(),e.snapshot(),owner,location),NONE);
 }
 public void recoverAll()throws IOException {
  Map<UUID,Entry> next=new LinkedHashMap<>();for(Entry e:objects.values())next.put(e.id(),new Entry(e.id(),e.snapshot(),null,e.location()));
  commit(next,NONE);
 }
 private void free(UUID owner){if(owner!=null&&held(owner)!=null)throw new IllegalStateException("Hands occupied");}
 private Entry required(UUID id){Entry e=objects.get(id);if(e==null)throw new IllegalStateException("Stale object");return e;}
 private Entry owned(UUID id,UUID owner){Entry e=required(id);if(!Objects.equals(owner,e.owner())||owner==null)throw new IllegalStateException("Ownership changed");return e;}
 private void replace(Entry e,Effect effect)throws IOException{Map<UUID,Entry> next=new LinkedHashMap<>(objects);next.put(e.id(),e);commit(next,effect);}
    private void commit(Map<UUID, Entry> next, Effect effect) throws IOException {
        try {
            effect.apply();
            store.save(next.values());
        } catch (IOException | RuntimeException failure) {
            try {
                effect.rollback();
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        objects = next;
        revision++;
        // Notifications cannot restore ownership after the new file became durable.
        try {
            effect.committed();
        } catch (RuntimeException failure) {
            System.getLogger(CarryLedger.class.getName()).log(System.Logger.Level.ERROR,
                    "Carry ownership committed, but native neighbor notification failed", failure);
        }
    }
 /** Versioned bounded binary records: private contents never enter sync JSON. */
 public static final class FileStore implements Store {
  private final Path file;
  public FileStore(Path file){this.file=file;}
  public List<Entry> load()throws IOException {
   if(!Files.exists(file))return List.of();if(Files.size(file)>134_217_728L)throw new IOException("Carry store exceeds safety limit");
   try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
    if(in.readInt()!=0x43415231)throw new IOException("Unknown carry store");int size=in.readInt();if(size<0||size>4096)throw new IOException("Invalid carry count");
    List<Entry> result=new ArrayList<>();for(int i=0;i<size;i++){
     UUID id=new UUID(in.readLong(),in.readLong());UUID owner=in.readBoolean()?new UUID(in.readLong(),in.readLong()):null;
     Location location=new Location(in.readUTF(),in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat());
     Snapshot snapshot=new Snapshot(in.readUTF(),in.readUTF(),text(in),text(in),bytes(in,2_097_152),in.readBoolean());
     result.add(new Entry(id,snapshot,owner,location));
    }if(in.read()!=-1)throw new IOException("Trailing carry bytes");return result;
   }catch(IllegalArgumentException e){throw new IOException("Invalid carry data",e);}
  }
  public void save(Collection<Entry> entries)throws IOException {
   Files.createDirectories(file.toAbsolutePath().getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");
   try{
    try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
     out.writeInt(0x43415231);out.writeInt(entries.size());for(Entry e:entries){
      out.writeLong(e.id().getMostSignificantBits());out.writeLong(e.id().getLeastSignificantBits());out.writeBoolean(e.owner()!=null);
      if(e.owner()!=null){out.writeLong(e.owner().getMostSignificantBits());out.writeLong(e.owner().getLeastSignificantBits());}
      Location l=e.location();out.writeUTF(l.world());out.writeDouble(l.x());out.writeDouble(l.y());out.writeDouble(l.z());out.writeFloat(l.yaw());
      Snapshot s=e.snapshot();out.writeUTF(s.kind());out.writeUTF(s.name());text(out,s.item());text(out,s.blockState());byte[] data=s.data();out.writeInt(data.length);out.write(data);out.writeBoolean(s.large());
     }
    }
    if(Files.size(temp)>134_217_728L)throw new IOException("Carry store exceeds safety limit");
    try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
    Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   }finally{Files.deleteIfExists(temp);}
  }
  private static void text(DataOutputStream out,String s)throws IOException{byte[] b=ua.rp.chat.carry.CarryWire.utf8(s,2_097_152);out.writeInt(b.length);out.write(b);}
  private static String text(DataInputStream in)throws IOException{return ua.rp.chat.carry.CarryWire.decode(bytes(in,2_097_152),2_097_152);}
  private static byte[] bytes(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("Invalid carry field size");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return b;}
 }
}
