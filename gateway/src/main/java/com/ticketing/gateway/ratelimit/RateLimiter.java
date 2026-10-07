package com.ticketing.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Keeps one {@link TokenBucket} per (rule, client) key in memory. That is enough while the gateway
 * is a single instance (it is pinned to one container); with several gateway replicas each would
 * count on its own and the buckets would have to move to a shared store such as Redis.
 */
public class RateLimiter {

    private static final int CLEANUP_EVERY_N_CALLS = 1024;

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final LongSupplier nanoClock;

    public RateLimiter() {
        this(System::nanoTime);
    }

    RateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public Decision check(String key, int capacity, int perMinute) {
        long now = nanoClock.getAsLong();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, perMinute, now));
        long waitNanos = bucket.tryConsume(now);

        if (calls.incrementAndGet() % CLEANUP_EVERY_N_CALLS == 0) {
            // Drop buckets nobody has touched since they refilled, so a stream of one-off client IPs can't grow the map forever.
            buckets.values().removeIf(b -> b.isFullAndIdle(now));
        }

        if (waitNanos == 0) {
            return Decision.ALLOWED;
        }
        return new Decision(false, Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L));
    }

    int size() {
        return buckets.size();
    }
}
