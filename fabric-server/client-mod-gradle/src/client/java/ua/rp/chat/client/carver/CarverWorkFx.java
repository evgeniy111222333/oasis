package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Local work storm over the authoritative server effects: the custom dust storm
 * thickens around the block while the simulated carving runs, plus a completion
 * burst. Server particles and sounds carry the scene for nearby players; this
 * thickens it for the artisan into a proper dust column without replacing anything.
 */
public final class CarverWorkFx {
    private static int tickCounter;

    private CarverWorkFx() {
    }

    public static void tick(Minecraft minecraft) {
        CarverDustScreen.clientTick();
        CarverDustCore.tick();
        if (minecraft.level == null || minecraft.player == null) return;
        tickCounter++;
        if (tickCounter % 4 != 0) return;
        if (CarverClientState.working() && CarverClientState.focus() != null) {
            BlockPos focus = CarverClientState.focus();
            CarverDustStorm.trickle(minecraft, focus, stormTint(minecraft, focus));
        }
        // Everyone sees the storm: nearby artisans get their own cocoon around the workpiece.
        for (CarverClientState.ObservedWork observed : CarverClientState.observedWorks()) {
            if (observed == null || observed.focus() == null) continue;
            CarverDustStorm.trickle(minecraft, observed.focus(),
                    stormTint(minecraft, observed.focus()));
        }
    }

    /** Material-biased dust tint of a focus block, for the screen veil. */
    public static int stormTintFor(BlockPos focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || focus == null) return 0xFFFFFF;
        return stormTint(minecraft, focus);
    }

    public static void burst(BlockPos focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || focus == null) return;
        CarverDustStorm.burst(minecraft, focus, stormTint(minecraft, focus));
    }

    public static void finish(BlockPos focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        CarverDustStorm.finish(minecraft, focus);
    }

    private static int stormTint(Minecraft minecraft, BlockPos focus) {
        try {
            net.minecraft.world.level.block.state.BlockState state =
                    minecraft.level.getBlockState(focus);
            if (ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(state)) {
                var cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
                if (cached != null && cached.volume != null) {
                    String dominant =
                            ua.rp.chat.microvoxel.MicrovoxelVolume.dominantMaterial(cached.volume);
                    if (dominant != null) {
                        state = ua.rp.chat.client.microvoxel.MicrovoxelSectionModel
                                .parseBlockState(dominant);
                    }
                }
            }
            return CarverDustStorm.tintFor(minecraft, focus, state);
        } catch (RuntimeException unreadable) {
            return 0xFFFFFF;
        }
    }
}
