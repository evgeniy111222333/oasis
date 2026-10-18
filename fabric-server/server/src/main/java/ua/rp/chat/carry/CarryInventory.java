package ua.rp.chat.carry;
import java.util.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
/** Capacity/identity preflight used by the actual native inventory transitions. */
public final class CarryInventory {
 private CarryInventory(){}
 public record Split(ItemStack hand,ItemStack remainder){}
 public static Split takeOne(ItemStack source,ItemStack hand){
  if(source.isEmpty())throw new IllegalStateException("Предмет недоступен.");if(!hand.isEmpty())throw new IllegalStateException("Освободите руку.");
  return new Split(source.copyWithCount(1),source.copyWithCount(source.getCount()-1));
 }
 public static Split transferOne(ItemStack expected,ItemStack source,ItemStack hand){
  if(!ItemStack.matches(expected,source))throw new IllegalStateException("Предмет изменился.");return takeOne(source,hand);
 }
 public static Plan stow(Container inventory,int excluded,ItemStack moving){
  int left=moving.getCount();Map<Integer,ItemStack> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
  int slots=Math.min(36,inventory.getContainerSize());
  for(int pass=0;pass<2&&left>0;pass++)for(int slot=0;slot<slots&&left>0;slot++){
   if(slot==excluded)continue;ItemStack existing=inventory.getItem(slot);
   if(pass==0?(existing.isEmpty()||!ItemStack.isSameItemSameComponents(existing,moving)):!existing.isEmpty())continue;
   int capacity=Math.min(moving.getMaxStackSize(),inventory.getMaxStackSize(moving))-existing.getCount();int amount=Math.min(left,Math.max(0,capacity));if(amount==0)continue;
   before.put(slot,existing.copy());after.put(slot,moving.copyWithCount(existing.getCount()+amount));left-=amount;
  }
  if(left>0)throw new IllegalStateException("Инвентарь заполнен.");return new Plan(inventory,before,after);
 }
 public record Plan(Container inventory,Map<Integer,ItemStack> before,Map<Integer,ItemStack> after) implements CarryLedger.Effect {
  public void apply(){after.forEach((slot,item)->inventory.setItem(slot,item.copy()));inventory.setChanged();}
  public void rollback(){before.forEach((slot,item)->inventory.setItem(slot,item.copy()));inventory.setChanged();}
 }
}
