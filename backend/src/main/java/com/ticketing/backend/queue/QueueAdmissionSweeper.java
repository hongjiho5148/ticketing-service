package com.ticketing.backend.queue;

import java.util.Collection;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Periodically lets the next batch of waiters through, up to however many active slots are free. */
@Component
public class QueueAdmissionSweeper {

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final int activeCapacity;
    private final long passTokenTtlMs;

    public QueueAdmissionSweeper(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.pass-token-ttl-ms}") long passTokenTtlMs) {
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.activeCapacity = activeCapacity;
        this.passTokenTtlMs = passTokenTtlMs;
    }

    @Scheduled(fixedRateString = "${queue.admission-interval-ms}")
    public void admitWaitingUsers() {
        for (String key : redissonClient.getKeys().getKeysByPattern("queue:waiting:*")) {
            Long eventId = Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
            admitForEvent(eventId);
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
