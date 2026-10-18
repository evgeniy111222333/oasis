package ua.rp.chat.microvoxel;

import java.util.UUID;

public record MicrovoxelKey(UUID worldId, int x, int y, int z) {
    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }
}
