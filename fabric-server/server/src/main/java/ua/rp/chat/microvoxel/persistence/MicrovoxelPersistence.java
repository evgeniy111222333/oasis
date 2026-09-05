package ua.rp.chat.microvoxel.persistence;

import net.minecraft.server.MinecraftServer;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Write-behind persistence pipeline. Dirty volumes are snapshotted on the server thread, appended
 * to the store journal on the dedicated save worker, acknowledged back on the server thread, and
 * compacted into region files when the journal grows past its threshold. Shutdown drains the
 * worker and writes the final snapshot synchronously.
 */
public final class MicrovoxelPersistence {
    private final RPChat plugin;
    private final MicrovoxelRuntime runtime;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rpchat-microvoxel-save");
        thread.setDaemon(true);
        return thread;
    });
    private boolean saveScheduled;
    private boolean shuttingDown;

    public MicrovoxelPersistence(RPChat plugin, MicrovoxelRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    public void markDirty(MicrovoxelKey key) {
        runtime.store().markDirty(key);
        schedulePersistence();
    }

    /**
     * Overflow hook for sibling stores with tiny data (voxel fluids). Runs on the save
     * worker after the journal/compact work, so fluid rewrites ride the already-coalesced
     * persistence rhythm instead of waking the disk on their own.
     */
    public void setOverflowSave(Runnable overflowSave) {
        this.overflowSave = overflowSave;
    }

    private Runnable overflowSave;

    /** Coalesced journal append; safe to call from any server-thread path. */
    public void schedulePersistence() {
        if (shuttingDown) return;
        // Dirty regions (partial-compact leftovers) schedule the worker even with an empty
        // entry map; the worker then only compacts without appending.
        if (saveScheduled || (!runtime.store().hasDirtyEntries()
                && runtime.store().dirtyRegionCount() == 0)) return;
        saveScheduled = true;
        MicrovoxelStore.DirtyBatch batch = runtime.store().snapshotDirty();
        saveExecutor.execute(() -> {
            boolean persisted = false;
            try {
                runtime.store().appendJournal(batch);
                persisted = true;
                if (runtime.store().shouldCompactJournal()) {
                    // Incremental compaction: bounded slices per worker run instead of one
                    // giant flush. Each slice is durable on its own; the journal drops only
                    // after the final slice, so a crash mid-compact replays the tail.
                    int slices = 0;
                    boolean clean = false;
                    while (!clean && slices < 8) {
                        clean = runtime.store().saveIncrementalSlice(4);
                        slices++;
                    }
                    if (clean) {
                        runtime.store().finishIncrementalSave();
                        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("store.compacts");
                    } else {
                        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("store.compacts.partial");
                    }
                    Runnable overflow = overflowSave;
                    if (overflow != null) {
                        try {
                            overflow.run();
                        } catch (RuntimeException overflowFailure) {
                            plugin.getLogger().warning("Overflow persistence failed: "
                                    + overflowFailure.getMessage());
                        }
                    }
                }
            } catch (IOException error) {
                plugin.getLogger().severe("Unable to persist microvoxels: " + error.getMessage());
            } finally {
                MinecraftServer server = plugin.getServer();
                if (server != null && !shuttingDown) {
                    boolean completed = persisted;
                    server.execute(() -> {
                        if (completed) runtime.store().acknowledge(batch);
                        saveScheduled = false;
                        // Leftover dirty regions from a partial compact must re-trigger the
                        // worker even when the entry-level dirty map was already acknowledged.
                        if (runtime.store().hasDirtyEntries()
                                || runtime.store().dirtyRegionCount() > 0) schedulePersistence();
                    });
                }
            }
        });
    }

    /**
     * Drains the save worker and writes the final snapshot. Shutdown-now is issued after the
     * grace period so a stuck worker can never race the synchronous final save and corrupt
     * a region file or delete a journal out from under an in-flight append.
     */
    public void shutdown() {
        shuttingDown = true;
        saveExecutor.shutdown();
        if (runtime.store() == null) return;
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out waiting for the microvoxel save worker; writing final snapshot now.");
                saveExecutor.shutdownNow();
            }
            synchronized (runtime.store()) {
                runtime.store().save();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while waiting for the microvoxel save worker.");
            try {
                runtime.store().save();
            } catch (IOException saveError) {
                plugin.getLogger().severe("Unable to save microvoxels during shutdown: " + saveError.getMessage());
            }
        } catch (IOException error) {
            plugin.getLogger().severe("Unable to save microvoxels during shutdown: " + error.getMessage());
        }
    }
}