package ua.rp.chat.microvoxel;

import java.io.IOException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import ua.rp.chat.RPChat;
import ua.rp.chat.carry.CarryBlockMutation;
import ua.rp.chat.carry.CarryVoxelRotation;

/** Carry uses the exact portable codec and the authoritative projection writer. */
public final class MicrovoxelCarryBridge {
    private final MicrovoxelManager manager;

    public MicrovoxelCarryBridge(MicrovoxelManager manager) {
        this.manager = manager;
    }

    private MicrovoxelKey key(ServerLevel level, BlockPos pos) {
        return new MicrovoxelKey(manager.runtime().worldId(level), pos.getX(), pos.getY(), pos.getZ());
    }

    public byte[] snapshot(ServerPlayer player, BlockPos pos) throws IOException {
        ServerLevel level = (ServerLevel) player.level();
        MicrovoxelKey key = key(level, pos);
        check(player, key);
        MicrovoxelVolume volume = manager.microvolumes().get(key);
        if (volume == null || volume.occupiedCount() == 0) throw new IOException("Sculpture unavailable");
        if (manager.fluidStore().get(key) != null) throw new IOException("Empty the sculpture's fluid first");
        for (Direction direction : Direction.values()) {
            if (manager.microvolumes().get(key(level, pos.relative(direction))) != null) {
                throw new IOException("Only isolated single-volume sculptures can be lifted");
            }
        }
        byte[] bytes = MicrovoxelManager.serializeVolume(volume);
        MicrovoxelPortableVolume.decode(bytes);
        return bytes;
    }

    private void check(ServerPlayer player, MicrovoxelKey key) throws IOException {
        if (!manager.runtime().storageReady() || manager.isProtected(key)
                || !RPChat.hasPermission(player, "rpchat.carry.sculpture", 0)) {
            throw new IOException("Sculpture protected");
        }
    }

    public void remove(ServerLevel level, BlockPos pos) throws IOException {
        MicrovoxelKey key = key(level, pos);
        try (var ignored = CarryBlockMutation.silentProjection()) {
            manager.runtime().projection().dematerialize(key);
            manager.runtime().sync().broadcastRemove(key);
            manager.microvolumes().save();
        }
    }

    public void restore(ServerLevel level, BlockPos pos, byte[] bytes, int turns) throws IOException {
        MicrovoxelKey key = key(level, pos);
        MicrovoxelVolume volume = CarryVoxelRotation.rotate(MicrovoxelPortableVolume.decode(bytes), turns);
        try (var ignored = CarryBlockMutation.silentProjection()) {
            manager.runtime().projection().materialize(key, volume);
            manager.runtime().sync().broadcastUpsert(key, volume);
            manager.microvolumes().save();
        }
    }

    public boolean canPlace(ServerPlayer player, BlockPos pos) {
        try {
            ServerLevel level = (ServerLevel) player.level();
            MicrovoxelKey key = key(level, pos);
            check(player, key);
            return manager.microvolumes().get(key) == null
                    && manager.microvolumes().countInChunk(key.worldId(), key.chunkX(), key.chunkZ())
                    < MicrovoxelRuntime.MAX_PER_CHUNK;
        } catch (Exception denied) {
            return false;
        }
    }
}
