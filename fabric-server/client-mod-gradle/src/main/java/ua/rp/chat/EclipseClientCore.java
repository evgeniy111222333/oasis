package ua.rp.chat;

import net.fabricmc.api.ModInitializer;
import ua.rp.chat.blood.BloodParticleTypes;

/** Registry bootstrap shared with the dedicated server's particle registry. */
public final class EclipseClientCore implements ModInitializer {
    @Override
    public void onInitialize() {
        BloodParticleTypes.register();
    }
}
