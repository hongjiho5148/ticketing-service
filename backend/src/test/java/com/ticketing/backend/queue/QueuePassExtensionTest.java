package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.messaging.ReservationEventListener;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * A pass is short (here 60 seconds) so that someone let in who does nothing gives the slot back soon, and is lengthened
 * (here to 300 seconds) for someone who has taken a seat so that they are not cut off in the middle of buying. Against
 * the compose Redis and MySQL; run inside the compose network with DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {
    "queue.active-capacity=5",
    "queue.admission-interval-ms=3600000",
    "queue.pass-token-ttl-ms=60000",
    "queue.pass-extension-ms=300000"
})
@Transactional
class QueuePassExtensionTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private ReservationEventListener listener;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 2_500_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId,
                QueueService.seenKey(eventId));
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t, "queue:first-reservation:" + t));
    }

    private long enterAndAdmit(long user) {
        tokens.add(queueService.enter(user, eventId).queueToken());
        sweeper.admitForEvent(eventId);
        return user;
    }

    private double expiresInSeconds(String token) {
        Double score = redissonClient.<String>getScoredSortedSet(QueueService.activeKey(eventId)).getScore(token);
        return (score - System.currentTimeMillis()) / 1000.0;
    }

    private static long user() {
        return 9_900_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    @Test
    void aPassIsShortUntilItsHolderTakesASeat() {
        long u = enterAndAdmit(user());
        String token = tokens.get(0);

        assertThat(expiresInSeconds(token)).isBetween(55.0, 61.0);

        assertThat(queueService.extendPassesOfHolder(u, eventId)).isEqualTo(1);

        assertThat(expiresInSeconds(token)).isBetween(295.0, 301.0);
    }

    @Test
    void takingAnotherSeatDoesNotShortenOrStackTheExtension() {
        long u = enterAndAdmit(user());
        String token = tokens.get(0);
        queueService.extendPassesOfHolder(u, eventId);
        redissonClient.<String>getScoredSortedSet(QueueService.activeKey(eventId)).add(System.currentTimeMillis() + 900_000, token); // already longer

        assertThat(queueService.extendPassesOfHolder(u, eventId)).isZero();

        assertThat(expiresInSeconds(token)).isBetween(895.0, 901.0);
    }

    @Test
    void aPassThatHasRunOutIsNotRevivedAndSomeoneWaitingHasNothingToExtend() {
        long expired = enterAndAdmit(user());
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(QueueService.activeKey(eventId));
        active.add(System.currentTimeMillis() - 1_000, tokens.get(0)); // ran out a second ago
        long notAdmitted = user();

        assertThat(queueService.extendPassesOfHolder(expired, eventId)).isZero();
        assertThat(queueService.isPassTokenValid(eventId, tokens.get(0))).isFalse();
        assertThat(queueService.extendPassesOfHolder(notAdmitted, eventId)).isZero(); // never queued
    }

    @Test
    void theReservationCreatedEventExtendsTheHoldersPass() {
        long u = enterAndAdmit(user());
        String token = tokens.get(0);
        String payload = "{\"reservationId\":1,\"seatId\":2,\"userId\":" + u + ",\"eventId\":" + eventId
                + ",\"createdAt\":\"" + LocalDateTime.now() + "\"}";

        listener.onReservationCreated(payload);

        assertThat(expiresInSeconds(token)).isBetween(295.0, 301.0);
    }
}
