package ua.rp.chat.blood;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class BloodParticleTypes {
    public static final SimpleParticleType DROP = register("blood_drop");
    public static final SimpleParticleType DECAL = register("blood_decal");
    public static final SimpleParticleType WOUND = register("blood_wound");

    private BloodParticleTypes() {
    }

    public static void register() {
        // Class initialization performs the registrations.
    }

    private static SimpleParticleType register(String path) {
        return Registry.register(
                BuiltInRegistries.PARTICLE_TYPE,
                Identifier.fromNamespaceAndPath("eclipseclient", path),
                FabricParticleTypes.simple(false)
        );
    }
}
