package ua.rp.chat.microvoxel;

/**
 * Pure rendering contract for a synchronized microvoxel volume anchor.
 *
 * <p>The boolean form deliberately has no Minecraft registry dependency, so migrations and
 * client-prediction behaviour can be verified before the game registries are bootstrapped.</p>
 */
public final class MicrovoxelAnchorRules {
    private MicrovoxelAnchorRules() {
    }

    public static boolean renderable(
            boolean nativeMarker,
            boolean legacyStructureVoid,
            boolean legacyLight,
            boolean predictedAir
    ) {
        return nativeMarker || legacyStructureVoid || legacyLight || predictedAir;
    }
}
