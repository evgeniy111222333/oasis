package ua.rp.chat.client.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelPart.Cube.class)
public interface ModelPartCubeAccessor {
    @Mutable
    @Accessor("polygons")
    void eclipse$setPolygons(ModelPart.Polygon[] polygons);
}
