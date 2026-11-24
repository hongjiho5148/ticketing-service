package com.ticketing.eventservice.seat;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 좌석별 Redis 잠금(키 {@code lock:seat:<seatId>})으로 좌석을 바꾸는 임계 구역을 지킨다. 요청이 크게 몰려도
 * 좌석당 한 번에 요청 하나만 데이터베이스에 닿고, 나머지는 MySQL에 쌓이는 대신 SEAT_ALREADY_RESERVED로
 * 곧바로 실패한다. 호출하는 쪽은 트랜잭션을 잠금 안에서 실행하므로(SeatInternalService.hold 참고) 잠금을 풀기 전에
 * 커밋이 끝난다. Seat의 @Version 낙관적 락은 그 아래에서 다중 방어로 그대로 둔다. 예를 들어 느린 요청이 아직
 * 실행 중인데 임대가 끝난 경우를 위해서다.
 */
@Component
public class SeatLockService {

    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);

    private final RedissonClient redissonClient;
    private final long waitMs;
    private final long leaseMs;

    public SeatLockService(
            RedissonClient redissonClient,
            @Value("${redis.seat-lock.wait-ms:500}") long waitMs,
            @Value("${redis.seat-lock.lease-ms:3000}") long leaseMs) {
        this.redissonClient = redissonClient;
        this.waitMs = waitMs;
        this.leaseMs = leaseMs;
    }

    public <T> T executeWithLock(Long seatId, Supplier<T> action) {
        RLock lock = redissonClient.getLock("lock:seat:" + seatId);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
        }
        if (!acquired) {
            throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
        }
        try {
            return action.get();
        } finally {
            release(lock, seatId);
        }
    }

    /**
     * 락을 푼다. 푸는 데 실패해도 요청을 실패시키지 않는다. 여기까지 왔다면 action(DB 트랜잭션)은 이미 커밋됐거나 자기 예외로
     * 끝난 뒤인데, 이 단계의 Redis 오류가 예외로 번지면 좌석은 HOLD로 바뀌었는데 요청은 500으로 끝난다. 장애 시험에서 실제로
     * 일어났다: Redis가 죽은 동안 좌석 3개가 예약 없이 HOLD로 남았다. 락은 임대 시간(lease-ms) 뒤에 저절로 풀리므로 못 푼
     * 대가는 그 시간 동안 같은 좌석의 다른 요청이 기다리다 충돌로 거절되는 것뿐이다.
     */
    private void release(RLock lock, Long seatId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException e) {
            // 임대가 이미 끝나서 잠금이 다른 곳에 다시 배정됐다. 풀 것이 없다.
        } catch (RuntimeException e) {
            // Redis 오류(RedisException 등). 락은 임대가 끝나면 풀린다.
            log.warn("Could not release the seat lock for seat {} (it expires by itself within {}ms): {}",
                    seatId, leaseMs, e.getClass().getSimpleName());
        }
    }
}
