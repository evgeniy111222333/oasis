package ua.rp.chat.carver;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Particle registry of the carver work storm. Client-spawned like the blood
 * particles: the server never sees these, every observer simulates its own
 * stylized cloud from the same impact event.
 */
public final class CarverParticleTypes {
    public static final SimpleParticleType DUST = register("carver_dust");

    private CarverParticleTypes() {
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
