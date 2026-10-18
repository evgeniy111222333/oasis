package ua.rp.chat.client.microvoxel;

/**
 * Immutable authoritative fluid snapshot for one volume, water or lava, extracted from
 * {@link MicrovoxelClientState}. The {@code levels} array is defensively cloned on construction
 * and read by terrain workers, so instances are safe to share across threads.
 */
public record MicrovoxelFluidView(byte[] levels, int revision, boolean lava) {
    public MicrovoxelFluidView {
        levels = levels.clone();
    }

    public int level(int cell) {
        return Byte.toUnsignedInt(levels[cell]);
    }
}
