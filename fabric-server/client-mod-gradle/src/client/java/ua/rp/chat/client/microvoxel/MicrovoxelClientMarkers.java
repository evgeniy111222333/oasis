package ua.rp.chat.client.microvoxel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rendered-marker reconciliation, extracted from {@link MicrovoxelClientState}.
 *
 * <p>A terrain section can render a marker whose volume has not arrived (out-of-order packets, a
 * lost snapshot entry, a chunk reload). Each such position is recorded and retried on a bounded
 * cadence — first a chunk resync, then a targeted volume resync — until the volume appears or the
 * attempt budget runs out. The actual resync requests are issued through a {@link ResyncSink} so
 * this class stays decoupled from the sync/control path.</p>
 */
public final class MicrovoxelClientMarkers {
    /** Grace before the first retry: gives the normal snapshot packet time to land. */
    private static final int GRACE_TICKS = 10;
    /** After a chunk resync, wait this long before a targeted volume resync. */
    private static final int TARGETED_RETRY_TICKS = 50;
    private static final int MAX_MARKERS = 256;
    private static final int MAX_ATTEMPTS = 4;

    /** Issued when a retry is due; implemented by the facade over its control/resync actions. */
    public interface ResyncSink {
        void chunkResync(int chunkX, int chunkZ, String reason);

        void volumeResync(BlockPos position, String reason);
    }

    private static final Map<BlockPos, MissingMarker> MISSING_MARKERS = new ConcurrentHashMap<>();

    private MicrovoxelClientMarkers() {
    }

    /** Records a rendered marker with no authoritative volume yet. */
    public static void note(BlockPos position, int tick) {
        BlockPos immutable = position.immutable();
        if (MicrovoxelClientVolumeStore.VOLUMES.containsKey(immutable)) return;
        if (MISSING_MARKERS.size() >= MAX_MARKERS) return;
        MISSING_MARKERS.putIfAbsent(immutable, new MissingMarker(tick, tick + GRACE_TICKS, 0));
    }

    /** Retries a bounded number of recorded markers. Returns after at most 32 inspections. */
    public static void process(ClientLevel level, int tick, boolean canResync, ResyncSink sink) {
        if (level == null || MISSING_MARKERS.isEmpty()) return;
        int inspected = 0;
        for (Map.Entry<BlockPos, MissingMarker> entry : MISSING_MARKERS.entrySet()) {
            if (inspected++ >= 32) break;
            BlockPos position = entry.getKey();
            MissingMarker missing = entry.getValue();
            if (MicrovoxelClientVolumeStore.VOLUMES.containsKey(position)
                    || !MicrovoxelBlocks.isMarker(level.getBlockState(position))) {
                MISSING_MARKERS.remove(position, missing);
                continue;
            }
            if (tick - missing.firstSeenTick() > MicrovoxelClientState.readyRetryTicks() * 10
                    || missing.attempts() >= MAX_ATTEMPTS) {
                MISSING_MARKERS.remove(position, missing);
                continue;
            }
            if (tick < missing.nextAttemptTick() || !canResync) continue;
            if (missing.attempts() == 0) {
                sink.chunkResync(position.getX() >> 4, position.getZ() >> 4,
                        "rendered marker has no volume");
                MISSING_MARKERS.replace(position, missing,
                        new MissingMarker(missing.firstSeenTick(), tick + TARGETED_RETRY_TICKS, 1));
            } else {
                sink.volumeResync(position, "marker still has no volume after chunk resync");
                MISSING_MARKERS.replace(position, missing,
                        new MissingMarker(missing.firstSeenTick(),
                                tick + MicrovoxelClientState.readyRetryTicks(), missing.attempts() + 1));
            }
        }
    }

    public static void remove(BlockPos position) {
        MISSING_MARKERS.remove(position);
    }

    public static void clear() {
        MISSING_MARKERS.clear();
    }

    private record MissingMarker(int firstSeenTick, int nextAttemptTick, int attempts) {
    }
}
