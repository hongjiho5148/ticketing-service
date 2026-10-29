package com.ticketing.backend.queue;

import java.time.LocalDateTime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBatch;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionSweeper.class);

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final int activeCapacity;
    private final long passTokenTtlMs;
    private final long abandonGraceMs;
    private final long sweepLockLeaseMs;
    private final QueueStreams queueStreams;
    private final Counter admittedCounter;
    private final Counter abandonedCounter;

    public QueueAdmissionSweeper(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.pass-token-ttl-ms}") long passTokenTtlMs,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs,
            @Value("${queue.abandon-grace-ms:60000}") long abandonGraceMs,
            QueueStreams queueStreams,
            MeterRegistry meterRegistry) {
        this.queueStreams = queueStreams;
        this.abandonGraceMs = abandonGraceMs;
        this.abandonedCounter = Counter.builder("queue_abandoned_total")
                .description("Waiting people taken out of the line because they stopped showing up").register(meterRegistry);
        this.admittedCounter = Counter.builder("queue_admitted_total").description("People let in from the queue").register(meterRegistry);
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.activeCapacity = activeCapacity;
        this.passTokenTtlMs = passTokenTtlMs;
        // Sized independently of the tick interval (with a floor) so a slow sweep under heavy DB
        // load doesn't outlive its own lease - that raced with the finally-block unlock below and
        // surfaced as IllegalMonitorStateException under the 5000-VU scale-out load test.
        this.sweepLockLeaseMs = Math.max(admissionIntervalMs * 4, 2000);
    }

    // The first run waits one interval like every later one: nobody is queued the moment the service starts, and a sweep
    // kicking off in the background right after startup would let in people a caller is about to look at (it made tests
    // that queue people up straight after the context starts fail now and then).
    @Scheduled(initialDelayString = "${queue.admission-interval-ms}", fixedRateString = "${queue.admission-interval-ms}")
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

    /**
     * Takes out of the line the people who have not been heard from for the grace period: their browser neither holds a
     * stream open nor asks for its status any more. Without this they stay in the line and, when their turn comes, are
     * let in and sit on a slot for the whole pass lifetime while someone who is actually waiting stays behind them.
     * Does nothing when the grace is 0 or less. A person taken out who comes back finds their token gone and joins again
     * at the back.
     */
    void removeAbandoned(Long eventId) {
        if (abandonGraceMs <= 0) {
            return;
        }
        RScoredSortedSet<String> seen = redissonClient.getScoredSortedSet(QueueService.seenKey(eventId));
        Collection<String> stale = seen.valueRange(
                Double.NEGATIVE_INFINITY, true, System.currentTimeMillis() - abandonGraceMs, true, 0, 1000);
        if (stale.isEmpty()) {
            return;
        }
        // Counted by what was really still in the line: a person let in a moment ago can still have a stale note here.
        RBatch batch = redissonClient.createBatch();
        for (String token : stale) {
            batch.getScoredSortedSet(QueueService.waitingKey(eventId)).removeAsync(token);
        }
        long removed = batch.execute().getResponses().stream().filter(Boolean.TRUE::equals).count();
        seen.removeAll(stale);
        if (removed == 0) {
            return;
        }
        abandonedCounter.increment(removed);
        log.info("took {} people who stopped showing up out of the line of event {}", removed, eventId);
        try {
            waitingQueueRepository.markAbandoned(stale);
        } catch (RuntimeException e) {
            log.warn("could not mark {} abandoned entries of event {}", stale.size(), eventId, e);
        }
    }

    void admitForEvent(Long eventId) {
        removeAbandoned(eventId);
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
        }
        admittedCounter.increment(toAdmit.size());
        redissonClient.getScoredSortedSet(QueueService.seenKey(eventId)).removeAll(toAdmit);
        // Tell the connections waiting on any instance. A failure here only means they hear it at the next refresh.
        try {
            queueStreams.announceAdmitted(eventId, expiresAt, toAdmit);
        } catch (RuntimeException e) {
            log.warn("could not announce {} admitted entries of event {}", toAdmit.size(), eventId, e);
        }
        // Not a @Transactional method of this class: it is called from admitWaitingUsers() on this same object, so
        // Spring's proxy would never intercept it and nothing would be saved. The repository call opens its own
        // transaction, and it is one UPDATE for the whole batch.
        // The history is only stats: if the DB hiccups, the people already let in Redis stay in and other events go on.
        try {
            waitingQueueRepository.markPassed(toAdmit, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("could not mark {} admitted entries of event {} PASSED", toAdmit.size(), eventId, e);
        }
    }
}
