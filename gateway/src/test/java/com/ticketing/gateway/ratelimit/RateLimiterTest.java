package com.ticketing.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RateLimiter limiter = new RateLimiter(now::get);

    private void advanceSeconds(long seconds) {
        now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    void allowsABurstUpToCapacityThenRefuses() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.check("k", 5, 60).allowed()).isTrue();
        }
        RateLimiter.Decision refused = limiter.check("k", 5, 60);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1); // 60/min earns a token every second
    }

    @Test
    void tokensComeBackAtTheRefillRate() {
        for (int i = 0; i < 5; i++) {
            limiter.check("k", 5, 60);
        }
        assertThat(limiter.check("k", 5, 60).allowed()).isFalse();

        advanceSeconds(1);
        assertThat(limiter.check("k", 5, 60).allowed()).isTrue();
        assertThat(limiter.check("k", 5, 60).allowed()).isFalse();

        advanceSeconds(3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check("k", 5, 60).allowed()).isTrue();
        }
        assertThat(limiter.check("k", 5, 60).allowed()).isFalse();
    }

    @Test
    void refillNeverExceedsCapacity() {
        limiter.check("k", 3, 60);
        advanceSeconds(3600);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check("k", 3, 60).allowed()).isTrue();
        }
        assertThat(limiter.check("k", 3, 60).allowed()).isFalse();
    }

    @Test
    void retryAfterReflectsASlowRefillRate() {
        limiter.check("k", 1, 5); // one token, earns one every 12 seconds
        RateLimiter.Decision refused = limiter.check("k", 1, 5);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(12);
    }

    @Test
    void clientsAreCountedSeparately() {
        assertThat(limiter.check("login|1.1.1.1", 1, 1).allowed()).isTrue();
        assertThat(limiter.check("login|1.1.1.1", 1, 1).allowed()).isFalse();
        assertThat(limiter.check("login|2.2.2.2", 1, 1).allowed()).isTrue();
        assertThat(limiter.check("seat|1.1.1.1", 1, 1).allowed()).isTrue();
    }

    @Test
    void idleRefilledBucketsAreEvicted() {
        for (int i = 0; i < 100; i++) {
            limiter.check("client-" + i, 5, 60);
        }
        assertThat(limiter.size()).isEqualTo(100);

        advanceSeconds(600); // every bucket has long since refilled
        for (int i = 0; i < 1024; i++) {
            limiter.check("busy", 100_000, 6_000_000); // enough calls to trigger a cleanup pass
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(2);
    }
}
