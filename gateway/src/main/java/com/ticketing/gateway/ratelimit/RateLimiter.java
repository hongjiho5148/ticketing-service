package com.ticketing.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * (규칙, 클라이언트) 키마다 {@link TokenBucket} 하나를 메모리에 둔다. 게이트웨이가 인스턴스 하나일 때는(컨테이너
 * 하나에 고정되어 있다) 충분하다. 게이트웨이 복제본이 여럿이면 각자 따로 세므로 버킷을 Redis 같은 공유 저장소로
 * 옮겨야 한다.
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
            // 다시 가득 찬 뒤로 아무도 건드리지 않은 버킷은 버린다. 일회성 클라이언트 IP가 이어져도 맵이 영원히 커지지 않게 한다.
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
