package ua.rp.chat.client.microvoxel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side microvoxel observability. Mirrors the server {@code MicrovoxelMetrics} naming so
 * both log lines can be correlated during load sessions: predictions vs authoritative edits,
 * rollbacks vs rejects, resync requests vs resync serves.
 *
 * <p>All counters are lock-free atomics; {@link #summarize()} renders one line per minute from
 * the client tick. No per-frame work happens here.</p>
 */
public final class MicrovoxelClientMetrics {
    private static final ConcurrentHashMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private MicrovoxelClientMetrics() {
    }

    /** Records one occurrence of a bounded event name. Thread-safe, lock-free. */
    public static void inc(String event) {
        COUNTERS.computeIfAbsent(event, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** Adds a measured delta (microseconds, volumes) to a cumulative counter. */
    public static void add(String event, long delta) {
        COUNTERS.computeIfAbsent(event, ignored -> new AtomicLong()).addAndGet(delta);
    }

    public static long get(String event) {
        AtomicLong counter = COUNTERS.get(event);
        return counter == null ? 0L : counter.get();
    }

    /** Renders one compact status line for the per-minute client log. */
    public static String summarize() {
        StringBuilder out = new StringBuilder(256);
        out.append("[MICROVOXEL-CLIENT-METRICS]");
        for (Map.Entry<String, AtomicLong> entry : COUNTERS.entrySet()) {
            out.append(' ').append(entry.getKey()).append('=').append(entry.getValue().get());
        }
        return out.toString();
    }
}
