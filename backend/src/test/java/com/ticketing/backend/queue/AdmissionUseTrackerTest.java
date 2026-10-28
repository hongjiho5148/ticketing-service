package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measuring what admitted people do with their slot: the time to the first seat is recorded once per admission, and only
 * for people who really were let in from the queue. Against the compose Redis and MySQL; run inside the compose network
 * with DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {"queue.active-capacity=1", "queue.admission-interval-ms=3600000"})
@Transactional
class AdmissionUseTrackerTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private AdmissionUseTracker tracker;
    @Autowired private RedissonClient redissonClient;
    @Autowired private MeterRegistry meters;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 3_500_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId,
                QueueService.seenKey(eventId));
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t, "queue:first-reservation:" + t));
    }

    private Timer timer() {
        return meters.get("queue_admission_to_first_reservation_seconds").timer();
    }

    private double reserved() {
        return meters.get("queue_admitted_reserved_total").counter().count();
    }

    @Test
    void theFirstReservationAfterBeingLetInIsTimedOnce() {
        long user = 9_600_000_000L + SEQ.incrementAndGet() % 1_000_000;
        tokens.add(queueService.enter(user, eventId).queueToken());
        sweeper.admitForEvent(eventId);
        long countBefore = timer().count();
        double reservedBefore = reserved();

        tracker.onReservationCreated(user, eventId, LocalDateTime.now().plusSeconds(42).toString());
        tracker.onReservationCreated(user, eventId, LocalDateTime.now().plusSeconds(90).toString()); // a second seat, or a redelivery

        assertThat(timer().count()).isEqualTo(countBefore + 1);
        assertThat(reserved()).isEqualTo(reservedBefore + 1);
        assertThat(timer().max(TimeUnit.SECONDS)).isBetween(40.0, 45.0);
    }

    @Test
    void someoneWhoWasNotLetInFromTheQueueIsNotCounted() {
        long first = 9_700_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long second = 9_700_000_000L + SEQ.incrementAndGet() % 1_000_000;
        tokens.add(queueService.enter(first, eventId).queueToken());
        tokens.add(queueService.enter(second, eventId).queueToken());
        sweeper.admitForEvent(eventId); // capacity 1: only the first is let in
        long countBefore = timer().count();

        tracker.onReservationCreated(second, eventId, LocalDateTime.now().toString()); // still waiting
        tracker.onReservationCreated(9_800_000_000L, eventId, LocalDateTime.now().toString()); // never queued

        assertThat(timer().count()).isEqualTo(countBefore);
    }
}
