package ua.rp.chat.carry;
/** Pure production validation gates; native adapters supply authoritative server facts. */
public final class CarryRules {
 public static final double REACH=3;
 private CarryRules(){}
 public static boolean actor(boolean authenticated,boolean alive,boolean spectator,boolean bound,boolean unconscious){return authenticated&&alive&&!spectator&&!bound&&!unconscious;}
 public static boolean nearby(boolean sameWorld,double distanceSquared,boolean fromVisible,boolean toVisible){return sameWorld&&Double.isFinite(distanceSquared)&&distanceSquared>=0&&distanceSquared<=REACH*REACH&&fromVisible&&toVisible;}
 public static int turns(float degrees){if(!Float.isFinite(degrees))throw new IllegalArgumentException("Invalid rotation");return Math.floorMod(Math.round((degrees%360)/90),4);}
}
