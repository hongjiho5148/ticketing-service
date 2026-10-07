package com.ticketing.gateway.ratelimit;

/**
 * A classic token bucket: holds up to {@code capacity} tokens, earns {@code perMinute} of them back
 * per minute, and every request spends one. A burst up to the capacity is fine; a sustained rate
 * above the refill rate drains it and gets refused. Time is passed in so tests can drive a fake clock.
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastNanos;

    TokenBucket(int capacity, int perMinute, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = perMinute / 60_000_000_000.0;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** Spends a token and returns 0, or - when empty - returns how many nanoseconds until one is available. */
    synchronized long tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / refillPerNano);
    }

    /** True once the bucket has been left alone long enough to be full again - i.e. it is indistinguishable from a new one. */
    synchronized boolean isFullAndIdle(long nowNanos) {
        refill(nowNanos);
        return tokens >= capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastNanos);
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        lastNanos = nowNanos;
    }
}
