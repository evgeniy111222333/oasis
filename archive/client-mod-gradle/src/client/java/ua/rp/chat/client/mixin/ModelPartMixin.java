package ua.rp.chat.client.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin {
    @Shadow private List<ModelPart.Cube> cubes;
    @Shadow private Map<String, ModelPart> children;

    @Inject(method = "getRandomCube(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/client/model/geom/ModelPart$Cube;", at = @At("HEAD"), cancellable = true)
    private void eclipse$getRandomCubeRecursively(RandomSource random, CallbackInfoReturnable<ModelPart.Cube> cir) {
        if (this.cubes.isEmpty()) {
            List<ModelPart.Cube> allCubes = new ArrayList<>();
            eclipse$collectCubes((ModelPart) (Object) this, allCubes);
            if (!allCubes.isEmpty()) {
                cir.setReturnValue(allCubes.get(random.nextInt(allCubes.size())));
            }
        }
    }

    @Unique
    private void eclipse$collectCubes(ModelPart part, List<ModelPart.Cube> list) {
        list.addAll(((ModelPartAccessor) (Object) part).getCubes());
        for (ModelPart child : ((ModelPartAccessor) (Object) part).getChildren().values()) {
            eclipse$collectCubes(child, list);
        }
    }
}
