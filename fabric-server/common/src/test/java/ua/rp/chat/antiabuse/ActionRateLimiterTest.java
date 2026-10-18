package ua.rp.chat.antiabuse;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic guard for the shared token-bucket limiter used by the server authority paths.
 * Uses an injected clock so burst, refill and per-key isolation are proven without sleeping.
 */
public final class ActionRateLimiterTest {
    public static void main(String[] args) {
        verifyBurstThenReject();
        verifyRefillOverTime();
        verifyKeysAreIsolated();
        verifyResetAndClear();
        verifyRejectsInvalidConfig();
        System.out.println("ActionRateLimiterTest passed");
    }

    private static void verifyBurstThenReject() {
        AtomicLong now = new AtomicLong(0L);
        ActionRateLimiter limiter = new ActionRateLimiter(10.0, 3.0, now::get);
        Object player = new Object();
        require(limiter.tryAcquire(player), "burst token 1");
        require(limiter.tryAcquire(player), "burst token 2");
        require(limiter.tryAcquire(player), "burst token 3");
        require(!limiter.tryAcquire(player), "burst must not exceed its size");
    }

    private static void verifyRefillOverTime() {
        AtomicLong now = new AtomicLong(0L);
        ActionRateLimiter limiter = new ActionRateLimiter(10.0, 1.0, now::get);
        Object player = new Object();
        require(limiter.tryAcquire(player), "first action spends the only token");
        require(!limiter.tryAcquire(player), "no token before refill");
        // 10 tokens/sec -> 100ms per token; refill only kicks in once a full token is available.
        now.addAndGet(50L);
        require(!limiter.tryAcquire(player), "half a token is not enough");
        now.addAndGet(60L);
        require(limiter.tryAcquire(player), "a refilled token must be spendable");
    }

    private static void verifyKeysAreIsolated() {
        AtomicLong now = new AtomicLong(0L);
        ActionRateLimiter limiter = new ActionRateLimiter(1.0, 1.0, now::get);
        Object first = new Object();
        Object second = new Object();
        require(limiter.tryAcquire(first), "first key token");
        require(!limiter.tryAcquire(first), "first key exhausted");
        require(limiter.tryAcquire(second), "second key must have its own bucket");
    }

    private static void verifyResetAndClear() {
        AtomicLong now = new AtomicLong(0L);
        ActionRateLimiter limiter = new ActionRateLimiter(1.0, 1.0, now::get);
        Object player = new Object();
        require(limiter.tryAcquire(player), "token before reset");
        limiter.reset(player);
        require(limiter.tryAcquire(player), "reset must restore a full bucket");
        limiter.clear();
        require(limiter.trackedKeys() == 0, "clear must drop every bucket");
    }

    private static void verifyRejectsInvalidConfig() {
        boolean rateRejected = false;
        try {
            new ActionRateLimiter(0.0, 1.0);
        } catch (IllegalArgumentException expected) {
            rateRejected = true;
        }
        require(rateRejected, "zero rate must be rejected");
        boolean burstRejected = false;
        try {
            new ActionRateLimiter(1.0, 0.0);
        } catch (IllegalArgumentException expected) {
            burstRejected = true;
        }
        require(burstRejected, "sub-one burst must be rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("ActionRateLimiterTest: " + message);
    }
}
