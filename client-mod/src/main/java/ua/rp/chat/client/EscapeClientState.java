package ua.rp.chat.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public final class EscapeClientState {
    private static Capabilities capabilities = Capabilities.FREE;
    private static EscapeProgress progress;
    private static String transientMessage = "";
    private static int transientTicks;
    private static boolean impulseDown; private static int impulseTaps; private static int impulseWindow;

    private EscapeClientState() {}
    public static void updateCapabilities(JsonObject json) {
        boolean bound = json.has("bound") && json.get("bound").getAsBoolean();
        if (!bound) { capabilities = Capabilities.FREE; progress = null; return; }
        capabilities = new Capabilities(true, text(json,"restraintMaterial","путы"), number(json,"restraintDurability",1), number(json,"restraintMax",1), bool(json,"canStruggle"), bool(json,"canBlade"), bool(json,"canEnvironment"), number(json,"escapeStamina",0));
    }
    public static void handle(JsonObject json) {
        boolean active=bool(json,"active"); String message=text(json,"message","");
        if(!active){progress=null;transientMessage=message;transientTicks=message.isBlank()?0:90;Minecraft c=Minecraft.getInstance();if(c.screen instanceof EscapeStruggleScreen)c.setScreen(null);return;}
        progress=new EscapeProgress(text(json,"mode","STRUGGLE"),clamp(number(json,"progress",0)),message,longNumber(json,"startedAt"),longNumber(json,"completeAt"),longNumber(json,"cycleStartedAt"),Math.max(1,longNumber(json,"cycleDurationMs")),clamp(number(json,"windowCenter",.5)),clamp(number(json,"windowWidth",.15)));
        if("STRUGGLE".equals(progress.mode)){Minecraft c=Minecraft.getInstance();if(!(c.screen instanceof EscapeStruggleScreen))c.setScreen(new EscapeStruggleScreen());}
    }
    public static void reset(){capabilities=Capabilities.FREE;progress=null;transientMessage="";transientTicks=0;impulseDown=false;impulseTaps=0;impulseWindow=0;}
    public static void clientTick(Minecraft c){if(c==null||c.player==null||!capabilities.bound||c.screen!=null||progress!=null){impulseDown=false;if(impulseWindow>0)impulseWindow--;return;}if(impulseWindow>0&&--impulseWindow==0)impulseTaps=0;long w=c.getWindow().handle();boolean impulse=GLFW.glfwGetKey(w,GLFW.GLFW_KEY_LEFT_CONTROL)==GLFW.GLFW_PRESS||GLFW.glfwGetKey(w,GLFW.GLFW_KEY_SPACE)==GLFW.GLFW_PRESS;if(impulse&&!impulseDown){impulseTaps++;impulseWindow=28;if(impulseTaps>=3&&capabilities.canStruggle){impulseTaps=0;impulseWindow=0;action(70);}}impulseDown=impulse;}
    public static boolean isBound(){return capabilities.bound;}
    public static Capabilities capabilities(){return capabilities;}
    public static EscapeProgress progress(){return progress;}
    public static void action(int action){Minecraft c=Minecraft.getInstance();if(c.player!=null)AcquaintanceClientState.send(action,c.player.getUUID(),"");}
    public static void render(GuiGraphicsExtractor g,int width,int height){Minecraft c=Minecraft.getInstance();if(c.player==null||c.font==null||c.screen!=null)return;if(transientTicks>0)transientTicks--;if(progress!=null&&!"STRUGGLE".equals(progress.mode)){int w=Math.min(344,width-42),x=width/2-w/2,y=height-116;g.fill(x,y,x+w,y+52,0xD815110D);g.fill(x,y,x+w,y+2,0xFFE3C099);g.fill(x+12,y+29,x+w-12,y+35,0xFF30271F);g.fill(x+12,y+29,x+12+(int)((w-24)*progress.progress),y+35,modeColor(progress.mode));g.centeredText(c.font,modeTitle(progress.mode),width/2,y+8,0xFFFFE8C5);g.centeredText(c.font,fit(c,progress.message,w-28),width/2,y+39,0xFFA5C3C4);return;}if(transientTicks>0&&!transientMessage.isBlank()){int w=Math.min(350,width-48),y=height-94;g.fill(width/2-w/2,y,width/2+w/2,y+28,0xD817130F);g.centeredText(c.font,fit(c,transientMessage,w-20),width/2,y+9,0xFFE3C099);return;}if(capabilities.bound){int y=height-70;String h="[G] Попытаться освободиться";int w=c.font.width(h)+24;g.fill(width/2-w/2,y-5,width/2+w/2,y+17,0xB014100D);g.fill(width/2-w/2,y-5,width/2+w/2,y-3,0xAAE3C099);g.centeredText(c.font,h,width/2,y+2,0xFFEAD1A8);}}
    private static int modeColor(String m){return switch(m){case"FIRE"->0xFFE07B42;case"STONE"->0xFFA5B0A6;case"HELP"->0xFF7FC5BD;default->0xFFD5B16F;};}
    private static String modeTitle(String m){return switch(m){case"BLADE"->"ПЕРЕРЕЗАНИЕ ПУТ";case"STONE"->"ТРЕНИЕ О КАМЕНЬ";case"FIRE"->"ОГОНЬ У ЗАПЯСТИЙ";case"HELP"->"РАЗВЯЗЫВАНИЕ УЗЛА";default->"ПОПЫТКА ОСВОБОДИТЬСЯ";};}
    private static String fit(Minecraft c,String v,int max){String r=v==null?"":v;while(!r.isEmpty()&&c.font.width(r)>max)r=r.substring(0,r.length()-1);return r.equals(v)?r:r+"...";}
    private static boolean bool(JsonObject j,String k){return j.has(k)&&j.get(k).getAsBoolean();}private static String text(JsonObject j,String k,String f){return j.has(k)?j.get(k).getAsString():f;}private static double number(JsonObject j,String k,double f){return j.has(k)?j.get(k).getAsDouble():f;}private static long longNumber(JsonObject j,String k){return j.has(k)?j.get(k).getAsLong():0;}private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    public record Capabilities(boolean bound,String material,double durability,double maxDurability,boolean canStruggle,boolean canBlade,boolean canEnvironment,double stamina){private static final Capabilities FREE=new Capabilities(false,"",0,0,false,false,false,0);}
    public record EscapeProgress(String mode,double progress,String message,long startedAt,long completeAt,long cycleStartedAt,long cycleDurationMs,double windowCenter,double windowWidth){}
}
