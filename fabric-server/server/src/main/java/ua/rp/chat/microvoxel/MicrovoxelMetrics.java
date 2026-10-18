package ua.rp.chat.microvoxel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single rendezvous point for microvoxel observability. Every subsystem records cheap atomic
 * counters here; {@link #summarize()} renders one log line per minute and {@code /microvoxel
 * status} embeds the same numbers for operators. No allocation happens on the hot path beyond
 * the counter update itself.
 *
 * <p>Counter naming: {@code edits.applied}, {@code edits.rejected.<reason>},
 * {@code net.delta/upsert/transaction/remove}, {@code sync.pages/snapshotVolumes/resync.*},
 * {@code mine.breaks}, {@code store.journal.*}, {@code collision.*}. Reasons are bounded string
 * constants defined at the call site, never player input.</p>
 */
public final class MicrovoxelMetrics {
    private static final MicrovoxelMetrics INSTANCE = new MicrovoxelMetrics();

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private MicrovoxelMetrics() {
    }

    public static MicrovoxelMetrics get() {
        return INSTANCE;
    }

    /** Records one occurrence of a bounded event name. Thread-safe, lock-free. */
    public static void inc(String event) {
        INSTANCE.counters.computeIfAbsent(event, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** Adds an arbitrary delta (bytes, volumes, milliseconds) to a gauge-like counter. */
    public static void add(String event, long delta) {
        INSTANCE.counters.computeIfAbsent(event, ignored -> new AtomicLong()).addAndGet(delta);
    }

    public static long get(String event) {
        AtomicLong counter = INSTANCE.counters.get(event);
        return counter == null ? 0L : counter.get();
    }

    /**
     * Renders one compact status line. Called once per minute from the facade tick and embedded
     * into the operator status command.
     */
    public static String summarize() {
        StringBuilder out = new StringBuilder(256);
        out.append("[MICROVOXEL-METRICS]");
        for (Map.Entry<String, AtomicLong> entry : INSTANCE.counters.entrySet()) {
            out.append(' ').append(entry.getKey()).append('=').append(entry.getValue().get());
        }
        return out.toString();
    }
}
