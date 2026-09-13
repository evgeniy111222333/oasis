package ua.rp.chat.client.render;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps vanilla stuck-projectile placement compatible with the articulated player rig. */
public final class StuckProjectileAttachment {
    private static final ModelPart EMERGENCY_TORSO = new ModelPart(
            List.of(new ModelPart.Cube(
                    0, 0,
                    -4.0f, 0.0f, -2.0f,
                    8.0f, 12.0f, 4.0f,
                    0.0f, 0.0f, 0.0f,
                    false, 64.0f, 64.0f, Set.of())),
            Map.of());

    private StuckProjectileAttachment() {
    }

    /**
     * Creates a real attachment cuboid with usable bounds and no rendered polygons.
     * ModelPart#getRandomCube can therefore place arrows while normal model rendering emits
     * no duplicate geometry.
     */
    public static CubeListBuilder invisibleCube(
            float x, float y, float z, float width, float height, float depth) {
        return CubeListBuilder.create().addBox(x, y, z, width, height, depth, Set.of());
    }

    public static ModelPart safeBodyPart(
            RandomSource random, ModelPart selected, List<ModelPart> bodyParts) {
        if (hasCube(selected)) {
            return selected;
        }

        List<ModelPart> populated = bodyParts.stream()
                .filter(StuckProjectileAttachment::hasCube)
                .toList();
        if (!populated.isEmpty()) {
            return populated.get(random.nextInt(populated.size()));
        }
        return EMERGENCY_TORSO;
    }

    public static boolean hasCube(ModelPart part) {
        return part != null && !part.isEmpty();
    }
}
