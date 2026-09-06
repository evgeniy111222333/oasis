package ua.rp.chat.microvoxel;

import java.util.UUID;

public record ChunkKey(UUID worldId, int x, int z) {
    public static ChunkKey of(MicrovoxelKey key) {
        return new ChunkKey(key.worldId(), key.chunkX(), key.chunkZ());
    }
}
