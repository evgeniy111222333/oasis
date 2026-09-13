package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class EscapeStruggleScreen extends Screen {
    private int ticks;
    private int flashTicks;

    public EscapeStruggleScreen() { super(Component.literal("Ослабление узлов")); }
    @Override public void tick(){ticks++;if(flashTicks>0)flashTicks--;}

    @Override
    public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        EscapeClientState.EscapeProgress p=EscapeClientState.progress();
        if(p==null)return;
        int cx=width/2,cy=height/2;
        g.fill(0,0,width,height,0x8A000000);
        int radius=Math.min(116,Math.min(width,height)/4);
        double phase=(System.currentTimeMillis()-p.cycleStartedAt())/(double)Math.max(1,p.cycleDurationMs());
        phase=Math.max(0,Math.min(1,phase));
        for(int i=0;i<120;i++){
            double t=i/119.0,a=-Math.PI*.82+t*Math.PI*1.64;
            int inner=radius-8,outer=radius;
            int color=Math.abs(t-p.windowCenter())<=p.windowWidth()/2?0xFFE3C099:(i%5==0?0xAA81715D:0x66594C3E);
            line(g,cx+(int)(Math.cos(a)*inner),cy+(int)(Math.sin(a)*inner),cx+(int)(Math.cos(a)*outer),cy+(int)(Math.sin(a)*outer),color);
        }
        double needleAngle=-Math.PI*.82+phase*Math.PI*1.64;
        line(g,cx,cy,cx+(int)(Math.cos(needleAngle)*(radius-15)),cy+(int)(Math.sin(needleAngle)*(radius-15)),flashTicks>0?0xFFFFFFFF:0xFFA5C3C4);
        g.fill(cx-5,cy-5,cx+6,cy+6,0xFFE3C099);
        int boxW=250;
        g.fill(cx-boxW/2,cy-radius-58,cx+boxW/2,cy-radius-20,0xE018140F);
        g.fill(cx-boxW/2,cy-radius-58,cx+boxW/2,cy-radius-55,0xFFE3C099);
        g.centeredText(font,"ОСЛАБЛЕНИЕ УЗЛОВ",cx,cy-radius-47,0xFFFFE8C5);
        g.centeredText(font,"Пробел — рывок в золотой зоне",cx,cy-radius-33,0xFFA5C3C4);
        int barW=220,y=cy+radius+24;
        g.fill(cx-barW/2,y,cx+barW/2,y+7,0xFF2A211A);
        g.fill(cx-barW/2,y,cx-barW/2+(int)(barW*p.progress()),y+7,0xFFD5B16F);
        g.centeredText(font,Math.round(p.progress()*100)+"% прочности разрушено",cx,y+14,0xFFB7A895);
        g.centeredText(font,"Esc — прекратить попытку",cx,y+31,0xFF81776E);
    }

    @Override public boolean keyPressed(KeyEvent event){if(event.key()==32){EscapeClientState.action(71);flashTicks=5;return true;}if(event.key()==256){EscapeClientState.action(74);onClose();return true;}return true;}
    @Override public void onClose(){if(minecraft!=null)minecraft.setScreen(null);}
    @Override public boolean isPauseScreen(){return false;}
    private void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int color){int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,err=dx+dy;while(true){g.fill(x0,y0,x0+2,y0+2,color);if(x0==x1&&y0==y1)break;int e2=2*err;if(e2>=dy){err+=dy;x0+=sx;}if(e2<=dx){err+=dx;y0+=sy;}}}
}
