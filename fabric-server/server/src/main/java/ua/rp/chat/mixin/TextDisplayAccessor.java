package ua.rp.chat.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("setText")
    void eclipseserver$setText(Component text);

    @Invoker("setLineWidth")
    void eclipseserver$setLineWidth(int width);

    @Invoker("setTextOpacity")
    void eclipseserver$setTextOpacity(byte opacity);

    @Invoker("setBackgroundColor")
    void eclipseserver$setBackgroundColor(int color);

    @Invoker("getFlags")
    byte eclipseserver$getFlags();

    @Invoker("setFlags")
    void eclipseserver$setFlags(byte flags);
}