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
        assertThat(refused.retryAfterSeconds()).isEqualTo(1); // 분당 60이면 1초에 토큰 하나를 얻는다
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
        limiter.check("k", 1, 5); // 토큰 하나, 12초에 하나씩 얻는다
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

        advanceSeconds(600); // 모든 버킷은 이미 한참 전에 다시 찼다
        for (int i = 0; i < 1024; i++) {
            limiter.check("busy", 100_000, 6_000_000); // 정리 작업을 일으키기에 충분한 호출 수
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(2);
    }
}
