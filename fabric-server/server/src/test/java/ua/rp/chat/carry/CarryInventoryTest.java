package ua.rp.chat.carry;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
public final class CarryInventoryTest {
 public static void main(String[] args){
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  // 26.1 separates item component binding from registry bootstrap (normally datapack loading).
  var components=net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,64).build();
  Items.APPLE.builtInRegistryHolder().bindComponents(components);Items.STICK.builtInRegistryHolder().bindComponents(components);
  ItemStack apples=new ItemStack(Items.APPLE,12);
  var split=CarryInventory.takeOne(apples,ItemStack.EMPTY);
  require(split.hand().getCount()==1&&split.remainder().getCount()==11&&apples.getCount()==12,"one item without source mutation");
  fails(()->CarryInventory.takeOne(apples,new ItemStack(Items.STICK)));
  fails(()->CarryInventory.transferOne(apples,new ItemStack(Items.APPLE,11),ItemStack.EMPTY));
  SimpleContainer bag=new SimpleContainer(3);bag.setItem(0,new ItemStack(Items.APPLE,63));bag.setItem(1,new ItemStack(Items.STICK,64));bag.setItem(2,new ItemStack(Items.STICK,64));
  CarryInventory.Plan plan=CarryInventory.stow(bag,-1,new ItemStack(Items.APPLE));plan.apply();require(bag.getItem(0).getCount()==64,"merge capacity");plan.rollback();require(bag.getItem(0).getCount()==63,"rollback exact count");
  fails(()->CarryInventory.stow(bag,0,new ItemStack(Items.APPLE)));
  require(bag.getItem(0).getCount()==63,"failed stow unchanged");
  var transfer=CarryInventory.transferOne(apples,apples,ItemStack.EMPTY);require(transfer.hand().getCount()+transfer.remainder().getCount()==12,"handoff conservation");
  System.out.println("CarryInventoryTest passed");
 }
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void fails(Runnable action){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Expected rejection");}
}
