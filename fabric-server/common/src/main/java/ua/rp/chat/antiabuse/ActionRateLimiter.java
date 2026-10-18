package ua.rp.chat.antiabuse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key token-bucket rate limiter shared by the server authority paths (microvoxel edits,
 * carver drafting, resync requests). Pure Java with no Minecraft dependency, so the exchange
 * logic is unit-tested without bootstrapping the game.
 *
 * <p>A bucket starts full ({@code burst} tokens) and refills at {@code ratePerSecond}. Each
 * accepted action spends one token; when the bucket is empty the action is rejected until the
 * next token refills. A burst smooths legitimate click storms while a strict rate bounds the
 * sustained flood an abusive client can produce.</p>
 *
 * <p>Thread-safety: the network callbacks and scheduled server tasks can both call in, so every
 * method synchronizes on the limiter. Contention is negligible (one map lookup per packet).</p>
 */
public final class ActionRateLimiter {
    private final double ratePerSecond;
    private final double burst;
    private final LongSupplier clockMillis;
    private final Map<Object, Bucket> buckets = new ConcurrentHashMap<>();

    public ActionRateLimiter(double ratePerSecond, double burst) {
        this(ratePerSecond, burst, System::currentTimeMillis);
    }

    /** Test seam: inject a deterministic clock. */
    public ActionRateLimiter(double ratePerSecond, double burst, LongSupplier clockMillis) {
        if (!(ratePerSecond > 0.0)) {
            throw new IllegalArgumentException("ratePerSecond must be positive");
        }
        if (!(burst >= 1.0)) {
            throw new IllegalArgumentException("burst must be at least 1");
        }
        if (clockMillis == null) {
            throw new IllegalArgumentException("clockMillis must not be null");
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.clockMillis = clockMillis;
    }

    /** Spends one token for {@code key}; false when the bucket is empty. */
    public synchronized boolean tryAcquire(Object key) {
        if (key == null) return true;
        long now = clockMillis.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now, burst));
        double elapsedSeconds = Math.max(0L, now - bucket.lastRefillMillis) / 1000.0;
        bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * ratePerSecond);
        bucket.lastRefillMillis = now;
        if (bucket.tokens < 1.0) return false;
        bucket.tokens -= 1.0;
        return true;
    }

    /** Rejection count is not tracked here; callers record their own metric. */
    public synchronized void reset(Object key) {
        if (key == null) return;
        buckets.remove(key);
    }

    public synchronized void clear() {
        buckets.clear();
    }

    public synchronized int trackedKeys() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        private Bucket(long now, double tokens) {
            this.tokens = tokens;
            this.lastRefillMillis = now;
        }
    }
}
