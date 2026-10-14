package ua.rp.chat;

import net.fabricmc.api.ModInitializer;
import ua.rp.chat.blood.BloodParticleTypes;
import ua.rp.chat.carver.CarverItems;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;

/** Registry bootstrap shared with the dedicated server's particle registry. */
public final class EclipseClientCore implements ModInitializer {
    @Override
    public void onInitialize() {
        MicrovoxelBlocks.register();
        BloodParticleTypes.register();
        CarverItems.register();
    }
}
