package com.ticketing.backend.queue;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically lets the next batch of waiters through, up to however many active slots are free.
 *
 * <p>Guarded by a Redis lock so that when the backend is scaled out to multiple instances, only
 * one of them runs a given sweep tick - otherwise every instance would admit its own batch from
 * the same waiting set, letting through up to (replica count x active-capacity) people instead of
 * just active-capacity.
 */
@Component
public class QueueAdmissionSweeper {

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final int activeCapacity;
    private final long passTokenTtlMs;
    private final long sweepLockLeaseMs;

    public QueueAdmissionSweeper(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.pass-token-ttl-ms}") long passTokenTtlMs,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs) {
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.activeCapacity = activeCapacity;
        this.passTokenTtlMs = passTokenTtlMs;
        // Sized independently of the tick interval (with a floor) so a slow sweep under heavy DB
        // load doesn't outlive its own lease - that raced with the finally-block unlock below and
        // surfaced as IllegalMonitorStateException under the 5000-VU scale-out load test.
        this.sweepLockLeaseMs = Math.max(admissionIntervalMs * 4, 2000);
    }

    @Scheduled(fixedRateString = "${queue.admission-interval-ms}")
    public void admitWaitingUsers() {
        RLock lock = redissonClient.getLock("lock:queue-sweep");
        boolean acquired;
        try {
            acquired = lock.tryLock(0, sweepLockLeaseMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            return; // another instance is already running this tick
        }
        try {
            for (String key : redissonClient.getKeys().getKeysByPattern("queue:waiting:*")) {
                Long eventId = Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
                admitForEvent(eventId);
            }
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

    @Transactional
    void admitForEvent(Long eventId) {
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(QueueService.activeKey(eventId));
        long now = System.currentTimeMillis();
        active.removeRangeByScore(0, true, now, true);

        int freeSlots = activeCapacity - active.size();
        if (freeSlots <= 0) {
            return;
        }

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(QueueService.waitingKey(eventId));
        Collection<String> toAdmit = waiting.valueRange(0, freeSlots - 1);
        if (toAdmit.isEmpty()) {
            return;
        }

        long expiresAt = now + passTokenTtlMs;
        for (String queueToken : toAdmit) {
            active.add(expiresAt, queueToken);
            waiting.remove(queueToken);
            waitingQueueRepository.findByQueueToken(queueToken).ifPresent(WaitingQueue::pass);
        }
    }
}
