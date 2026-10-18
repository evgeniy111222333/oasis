package ua.rp.chat.carry;
public final class CarryRulesTest {
 public static void main(String[] args){
  require(CarryRules.actor(true,true,false,false,false));
  require(!CarryRules.actor(false,true,false,false,false));require(!CarryRules.actor(true,false,false,false,false));require(!CarryRules.actor(true,true,true,false,false));require(!CarryRules.actor(true,true,false,true,false));require(!CarryRules.actor(true,true,false,false,true));
  require(CarryRules.nearby(true,9,true,true));require(!CarryRules.nearby(true,9.001,true,true));require(!CarryRules.nearby(false,1,true,true));require(!CarryRules.nearby(true,1,false,true));require(!CarryRules.nearby(true,Double.NaN,true,true));
  require(CarryRules.turns(91)==1&&CarryRules.turns(-90)==3&&CarryRules.turns(450)==1);
  boolean denied=false;try{CarryRules.turns(Float.NaN);}catch(IllegalArgumentException expected){denied=true;}require(denied);
  System.out.println("CarryRulesTest passed");
 }
 static void require(boolean value){if(!value)throw new AssertionError("Production validation failed");}
}
