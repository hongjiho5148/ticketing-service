package com.ticketing.gateway.ratelimit;

/**
 * 고전적인 토큰 버킷이다. 최대 {@code capacity}개의 토큰을 들고 있고, 분당 {@code perMinute}개씩 다시 얻으며,
 * 요청마다 하나를 쓴다. 용량까지의 버스트는 괜찮고, 보충 속도를 넘는 지속적인 속도는 버킷을 비워서 거절당한다.
 * 테스트가 가짜 시계를 쓸 수 있도록 시간은 바깥에서 넘겨받는다.
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

    /** 토큰을 쓰고 0을 돌려준다. 비어 있으면 하나가 생길 때까지 몇 나노초가 남았는지를 돌려준다. */
    synchronized long tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / refillPerNano);
    }

    /** 버킷이 다시 가득 찰 만큼 오래 방치됐으면 true다. 새 버킷과 구별할 수 없는 상태다. */
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
