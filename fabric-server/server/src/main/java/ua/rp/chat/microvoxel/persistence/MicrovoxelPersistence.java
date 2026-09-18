package ua.rp.chat.microvoxel.persistence;

import net.minecraft.server.MinecraftServer;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelMetrics;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Write-behind persistence pipeline. Dirty volumes are captured on the server thread as immutable
 * references (published volumes are frozen), appended to the store journal on the dedicated save
 * worker, acknowledged back on the server thread, and compacted into region files when the journal
 * grows past its threshold. Shutdown drains the worker and writes the final snapshot synchronously.
 *
 * <p>Every capture happens on the server thread and every byte the worker touches is immutable:
 * journal batches carry frozen references, region compaction reads frozen store volumes, and the
 * optional overflow (voxel fluids) is captured into an immutable snapshot that the worker only
 * serialises. This is what keeps a concurrent edit from tearing a persisted volume.</p>
 */
public final class MicrovoxelPersistence {
    /**
     * Journal frame caps. A burst of edits is split across frames instead of building a single
     * oversized batch: the old code threw on batches over 64 MB and retried the same batch
     * forever, wedging the worker. Slices also bound the server-thread capture cost.
     */
    private static final int JOURNAL_SLICE_MAX_ENTRIES = 4096;
    private static final long JOURNAL_SLICE_MAX_BYTES = 8L * 1024L * 1024L;
    /**
     * After this many back-to-back IO failures the worker stops rescheduling itself from the
     * completion callback, so a permanently failing disk (full / read-only) cannot spin the
     * worker and spam the log forever. Dirty entries stay in memory; the next real edit calls
     * {@link #schedulePersistence()} directly and retries.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final RPChat plugin;
    private final MicrovoxelRuntime runtime;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rpchat-microvoxel-save");
        thread.setDaemon(true);
        return thread;
    });
    /** Minimum spacing between backstop retries, so a dead disk is not hammered every tick. */
    private static final long RETRY_BACKSTOP_MILLIS = 5_000L;

    private boolean saveScheduled;
    private boolean shuttingDown;
    private volatile int consecutiveFailures;
    private volatile long lastRetryMillis;
    private OverflowStore overflowStore;

    public MicrovoxelPersistence(RPChat plugin, MicrovoxelRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    /**
     * Sibling store with tiny data (voxel fluids) that rides the coalesced persistence rhythm.
     * {@link #capture()} runs on the server thread and must return an immutable handle; the
     * returned handle's {@link Pending#write()} runs on the save worker and
     * {@link Pending#acknowledge()} on the server thread after a successful write.
     */
    public interface OverflowStore {
        Pending capture();

        interface Pending {
            void write() throws IOException;

            void acknowledge();
        }
    }

    public void setOverflowStore(OverflowStore overflowStore) {
        this.overflowStore = overflowStore;
    }

    /**
     * Persists the overflow (fluid) store asynchronously, independent of journal compaction.
     * The snapshot is captured on the calling (server) thread for tear-freedom; the serialize,
     * fsync and backup copy run on the save worker, so a large fluid rewrite never stalls a tick.
     */
    public void scheduleOverflowSave() {
        if (shuttingDown || overflowStore == null) return;
        OverflowStore.Pending overflow = overflowStore.capture();
        if (overflow == null) return;
        saveExecutor.execute(() -> {
            try {
                overflow.write();
                consecutiveFailures = 0;
                MinecraftServer server = plugin.getServer();
                if (server != null) {
                    server.execute(overflow::acknowledge);
                }
            } catch (IOException error) {
                int failures = ++consecutiveFailures;
                MicrovoxelMetrics.inc("store.persist.failed");
                if (failures == 1 || failures % MAX_CONSECUTIVE_FAILURES == 0) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Unable to persist microvoxel fluids (attempt " + failures + ")", error);
                }
            }
        });
    }

    public void markDirty(MicrovoxelKey key) {
        runtime.store().markDirty(key);
        schedulePersistence();
    }

    /**
     * Periodic backstop for the dead-letter case: after {@link #MAX_CONSECUTIVE_FAILURES} the
     * self-retry loop stops, so a transient disk failure would otherwise leave dirty volumes
     * unsaved until the next real edit. Called from the server tick; throttled so a permanently
     * failing disk is retried at most every {@link #RETRY_BACKSTOP_MILLIS}. {@link
     * #schedulePersistence()} itself no-ops when nothing is dirty.
     */
    public void retryIfNeeded() {
        if (shuttingDown || saveScheduled) return;
        long now = System.currentTimeMillis();
        if (now - lastRetryMillis < RETRY_BACKSTOP_MILLIS) return;
        lastRetryMillis = now;
        consecutiveFailures = 0;
        schedulePersistence();
    }

    /** Coalesced journal append; safe to call from any server-thread path. */
    public void schedulePersistence() {
        if (shuttingDown || saveScheduled) return;
        boolean hasDirtyEntries = runtime.store().hasDirtyEntries();
        boolean hasDirtyRegions = runtime.store().dirtyRegionCount() > 0;
        // The overflow (fluid) snapshot is deep-copied on the server thread, so only capture it
        // when this worker run will actually compact the journal — the same cadence the old
        // live-reading overflow ran at, without the tear.
        OverflowStore.Pending overflow = (overflowStore != null && runtime.store().shouldCompactJournal())
                ? overflowStore.capture()
                : null;
        if (!hasDirtyEntries && !hasDirtyRegions && overflow == null) return;

        MicrovoxelStore.DirtyBatch batch =
                runtime.store().snapshotDirty(JOURNAL_SLICE_MAX_ENTRIES, JOURNAL_SLICE_MAX_BYTES);
        if (batch.entries().isEmpty() && !hasDirtyRegions && overflow == null) return;

        saveScheduled = true;
        saveExecutor.execute(() -> runPersistence(batch, overflow));
    }

    private void runPersistence(MicrovoxelStore.DirtyBatch batch, OverflowStore.Pending overflow) {
        boolean batchPersisted = false;
        boolean overflowPersisted = false;
        try {
            runtime.store().appendJournal(batch);
            batchPersisted = true;
            consecutiveFailures = 0;
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
                    MicrovoxelMetrics.inc("store.compacts");
                } else {
                    MicrovoxelMetrics.inc("store.compacts.partial");
                }
            }
            // Safety valve: if compaction is outrun and the journal crosses the hard ceiling,
            // do one full durable flush (which drops the journal) rather than let it grow forever.
            if (runtime.store().journalAtHardCap()) {
                runtime.store().save();
                MicrovoxelMetrics.inc("store.compacts.hardcap");
            }
            if (overflow != null) {
                overflow.write();
                overflowPersisted = true;
            }
        } catch (IOException error) {
            int failures = ++consecutiveFailures;
            MicrovoxelMetrics.inc("store.persist.failed");
            if (failures == 1 || failures % MAX_CONSECUTIVE_FAILURES == 0) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Unable to persist microvoxels (attempt " + failures + ")", error);
            }
        } finally {
            boolean completed = batchPersisted;
            boolean overflowDone = overflowPersisted;
            int failures = consecutiveFailures;
            MinecraftServer server = plugin.getServer();
            if (server != null && !shuttingDown) {
                server.execute(() -> {
                    if (completed) runtime.store().acknowledge(batch);
                    if (overflowDone && overflow != null) overflow.acknowledge();
                    saveScheduled = false;
                    // Leftover dirty entries/regions from a bounded slice must re-trigger the
                    // worker even when this run acknowledged everything it captured. After a run
                    // of failures we stop the self-retry loop (dead-letter) to avoid a spin.
                    if ((runtime.store().hasDirtyEntries()
                            || runtime.store().dirtyRegionCount() > 0)
                            && failures < MAX_CONSECUTIVE_FAILURES) {
                        schedulePersistence();
                    }
                });
            }
        }
    }

    /**
     * Drains the save worker and writes the final snapshot. Shutdown-now is issued after the
     * grace period so a stuck worker can never race the synchronous final save and corrupt a
     * region file or delete a journal out from under an in-flight append.
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
