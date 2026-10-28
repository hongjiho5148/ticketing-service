package com.ticketing.backend.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * What people do with the slot they were let in for: how long after being admitted they hold their first seat, and how
 * many of the admitted ever do. Nothing here changes who gets in; it is the evidence for how long a pass should last (a
 * pass that outlives the time people really need, or that most never use, keeps others waiting).
 *
 * <p>Fed by the reservation-created event. Only the first reservation after an admission counts, and that is remembered
 * in Redis, so a person holding four seats is counted once and a redelivered message does not count twice.
 */
@Component
public class AdmissionUseTracker {

    private final WaitingQueueRepository waitingQueueRepository;
    private final RedissonClient redissonClient;
    private final Timer toFirstReservation;
    private final Counter admittedWhoReserved;

    public AdmissionUseTracker(WaitingQueueRepository waitingQueueRepository, RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.waitingQueueRepository = waitingQueueRepository;
        this.redissonClient = redissonClient;
        this.toFirstReservation = Timer.builder("queue_admission_to_first_reservation_seconds")
                .description("Time from being let in to holding the first seat")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofMinutes(30))
                .register(meterRegistry);
        this.admittedWhoReserved = Counter.builder("queue_admitted_reserved_total")
                .description("People let in from the queue who went on to hold a seat (counted once each)").register(meterRegistry);
    }

    /** @param createdAt when the reservation was made, an ISO-8601 local date-time as the event carries it */
    public void onReservationCreated(Long userId, Long eventId, String createdAt) {
        Optional<WaitingQueue> admission = waitingQueueRepository.findByUserIdAndEventId(userId, eventId).stream()
                .filter(entry -> entry.getStatus() == QueueEntryStatus.PASSED && entry.getPassedAt() != null)
                .max(Comparator.comparing(WaitingQueue::getPassedAt));
        if (admission.isEmpty()) {
            return; // not through the queue (or admitted before the history was kept)
        }
        RBucket<Boolean> counted = redissonClient.getBucket("queue:first-reservation:" + admission.get().getQueueToken());
        if (!counted.trySet(true, 2, TimeUnit.HOURS)) {
            return; // already counted this admission
        }
        Duration took = Duration.between(admission.get().getPassedAt(), LocalDateTime.parse(createdAt));
        toFirstReservation.record(took.isNegative() ? Duration.ZERO : took);
        admittedWhoReserved.increment();
    }
}
