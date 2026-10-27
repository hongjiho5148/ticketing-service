package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.messaging.KafkaTopics;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;

/**
 * The whole path on this side of the broker: a reservation.confirmed message published to Kafka reaches the listener,
 * which hands the buyer's slot back. Real Kafka, Redis and MySQL from the compose stack. The listener runs on its own
 * thread, so the rows it reads must be committed - this class is NOT @Transactional and deletes what it created. Run
 * inside the compose network with DB_HOST=mysql REDIS_HOST=redis KAFKA_BOOTSTRAP_SERVERS=kafka:9092.
 */
@SpringBootTest(properties = {"queue.active-capacity=5", "queue.admission-interval-ms=3600000"})
class ReservationConfirmedListenerTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private WaitingQueueRepository waitingQueueRepository;
    @Autowired private RedissonClient redissonClient;
    @Autowired @Qualifier("deadLetterKafkaTemplate") private KafkaTemplate<String, String> kafka;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 5_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        tokens.forEach(t -> {
            redissonClient.getKeys().delete("queue:event:" + t);
            waitingQueueRepository.findByQueueToken(t).ifPresent(waitingQueueRepository::delete);
        });
    }

    private String enter(long userId) {
        QueueEnterResponse response = queueService.enter(userId, eventId);
        tokens.add(response.queueToken());
        return response.queueToken();
    }

    private void confirmed(long userId) throws Exception {
        long reservationId = SEQ.incrementAndGet();
        String payload = "{\"reservationId\":" + reservationId + ",\"seatId\":1,\"userId\":" + userId
                + ",\"eventId\":" + eventId + ",\"confirmedAt\":\"2026-10-27T12:00:00\"}";
        kafka.send(KafkaTopics.RESERVATION_CONFIRMED, String.valueOf(reservationId), payload).get();
    }

    private boolean passValid(String token) {
        return queueService.isPassTokenValid(eventId, token);
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("the listener handed the slot back within 10s").isTrue();
    }

    @Test
    void aConfirmedReservationPublishedToKafkaHandsTheBuyersSlotBack() throws Exception {
        long buyer = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long other = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        String buyerToken = enter(buyer);
        String otherToken = enter(other);
        sweeper.admitForEvent(eventId); // the timer is parked, so let both in by hand
        assertThat(passValid(buyerToken)).isTrue();
        assertThat(passValid(otherToken)).isTrue();

        confirmed(buyer);

        awaitUntil(() -> !passValid(buyerToken));
        assertThat(passValid(otherToken)).isTrue();

        // the same message delivered a second time (at-least-once) changes nothing
        confirmed(buyer);
        Thread.sleep(1500);
        assertThat(passValid(otherToken)).isTrue();
    }

    /**
     * In the running service the sweeper admits people from a method it calls on itself, so no transaction is opened and
     * the history row is never marked PASSED. Admitting through the unproxied bean reproduces exactly that; the slot must
     * still come back.
     */
    @Test
    void theSlotIsHandedBackEvenThoughTheAdmissionNeverMarkedTheHistoryRowPassed() throws Exception {
        long buyer = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        String buyerToken = enter(buyer);
        AopTestUtils.<QueueAdmissionSweeper>getUltimateTargetObject(sweeper).admitForEvent(eventId);
        assertThat(passValid(buyerToken)).isTrue();
        assertThat(waitingQueueRepository.findByQueueToken(buyerToken).orElseThrow().getStatus())
                .isEqualTo(QueueEntryStatus.WAITING);

        confirmed(buyer);

        awaitUntil(() -> !passValid(buyerToken));
    }

    @Test
    void anUnreadableMessageDoesNotStopLaterOnesFromBeingHandled() throws Exception {
        long buyer = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        String buyerToken = enter(buyer);
        sweeper.admitForEvent(eventId);
        kafka.send(KafkaTopics.RESERVATION_CONFIRMED, "bad", "this is not json").get();

        confirmed(buyer);

        // the bad one is retried and dead-lettered (about 3 seconds); the good one behind it is then handled
        for (int i = 0; i < 200 && passValid(buyerToken); i++) {
            Thread.sleep(100);
        }
        assertThat(passValid(buyerToken)).isFalse();
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet("queue:active:" + eventId);
        assertThat(active.contains(buyerToken)).isFalse();
    }
}
