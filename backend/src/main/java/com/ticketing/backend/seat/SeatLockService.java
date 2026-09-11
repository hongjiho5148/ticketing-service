package com.ticketing.backend.seat;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guards the seat-reservation critical section with a per-seat Redis lock (key
 * {@code lock:seat:<seatId>}) so that, under heavy concurrent demand, only one request per seat
 * ever reaches the database at a time - everyone else fails fast with SEAT_ALREADY_RESERVED
 * instead of piling onto MySQL. The @Version optimistic lock on Seat stays in place underneath
 * this as the actual correctness guarantee (the lock is released a moment before the surrounding
 * @Transactional commits, so it narrows contention rather than fully eliminating the race).
 */
@Component
public class SeatLockService {

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
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (IllegalMonitorStateException e) {
                // Lease already expired and the lock was reassigned elsewhere - nothing to unlock.
            }
        }
    }
}
