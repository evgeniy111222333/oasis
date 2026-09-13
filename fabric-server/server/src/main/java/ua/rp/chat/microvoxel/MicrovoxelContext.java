package ua.rp.chat.microvoxel;

import ua.rp.chat.microvoxel.collision.MicrovoxelCollision;
import ua.rp.chat.microvoxel.persistence.MicrovoxelPersistence;
import ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub;

/**
 * Wired set of the four static services shared by the behaviour modules. The context is immutable
 * after construction and lets every module reach leaf services without holding a god reference.
 */
public final class MicrovoxelContext {
    private final MicrovoxelRuntime runtime;
    private final MicrovoxelSyncHub sync;
    private final MicrovoxelCollision collision;
    private final MicrovoxelPersistence persistence;

    public MicrovoxelContext(
            MicrovoxelRuntime runtime,
            MicrovoxelSyncHub sync,
            MicrovoxelCollision collision,
            MicrovoxelPersistence persistence) {
        this.runtime = runtime;
        this.sync = sync;
        this.collision = collision;
        this.persistence = persistence;
    }

    public MicrovoxelRuntime runtime() {
        return runtime;
    }

    public MicrovoxelSyncHub sync() {
        return sync;
    }

    public MicrovoxelCollision collision() {
        return collision;
    }

    public MicrovoxelPersistence persistence() {
        return persistence;
    }
}