package ua.rp.chat.microvoxel;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.fluid.FluidStore;
import ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub;

import java.util.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Shared mutable runtime context of the microvoxel subsystem. Owns the cross-module slices of
 * state (store, projection, save identity, storage health, server tick) and the leaf services
 * every module depends on (world identity, world lookup, persistence delegation, marker sink).
 *
 * <p>Construction is cheap and side-effect free; storage is only touched through
 * {@link #initialize(MicrovoxelStore, UUID, Path)} during the facade's {@code start()}.
 */
public final class MicrovoxelRuntime {
    /** Hard per-chunk volume quota enforced by edit and placement paths. */
    public static final int MAX_PER_CHUNK = 512;

    private final RPChat plugin;
    private MicrovoxelStore store;
    private MicrovoxelProjection projection;
    private MicrovoxelFlags flags;
    private FluidStore fluids;
    private UUID saveIdentity;
    private Path storagePath;
    private boolean storageAvailable;
    private long serverTick;
    private MicrovoxelSyncHub sync;

    public MicrovoxelRuntime(RPChat plugin) {
        this.plugin = plugin;
    }

    public void initialize(MicrovoxelStore store, UUID saveIdentity, Path storagePath) {
        this.store = store;
        this.saveIdentity = saveIdentity;
        this.storagePath = storagePath;
        this.storageAvailable = true;
    }

    public void setStorageUnavailable() {
        this.storageAvailable = false;
    }

    public void setProjection(MicrovoxelProjection projection) {
        this.projection = projection;
    }

    public void setFlags(MicrovoxelFlags flags) {
        this.flags = flags;
    }

    /** Protection flags; never null after facade start, null-safe before (no protection). */
    public MicrovoxelFlags flags() {
        return flags;
    }

    public void setSync(MicrovoxelSyncHub sync) {
        this.sync = sync;
    }

    public MicrovoxelSyncHub sync() {
        return sync;
    }

    public void setFluidStore(FluidStore fluids) {
        this.fluids = fluids;
    }

    /** Voxel fluid data; null before facade start (no fluids). */
    public FluidStore fluids() {
        return fluids;
    }

    public MinecraftServer server() {
        return plugin.getServer();
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    public MicrovoxelStore store() {
        return store;
    }

    public MicrovoxelProjection projection() {
        return projection;
    }

    public Path storagePath() {
        return storagePath;
    }

    public boolean storageReady() {
        return storageAvailable && store != null;
    }

    public boolean storageAvailable() {
        return storageAvailable;
    }

    public long serverTick() {
        return serverTick;
    }

    public long advanceTick() {
        return ++serverTick;
    }

    public UUID worldId(Level level) {
        if (saveIdentity == null) {
            throw new IllegalStateException("Microvoxel save identity is not initialized");
        }
        String scoped = saveIdentity + "|" + level.dimension();
        return UUID.nameUUIDFromBytes(scoped.getBytes(StandardCharsets.UTF_8));
    }

    static UUID legacyWorldId(Level level) {
        return UUID.nameUUIDFromBytes(level.dimension().toString().getBytes(StandardCharsets.UTF_8));
    }

    public ServerLevel getWorld(UUID worldId) {
        for (ServerLevel level : plugin.getServer().getAllLevels()) {
            if (worldId(level).equals(worldId)) {
                return level;
            }
        }
        return null;
    }
}