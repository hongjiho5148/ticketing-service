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
 * 입장권은 짧게(여기서는 60초) 두어서 입장하고 아무것도 하지 않는 사람이 슬롯을 곧 돌려주게 하고, 좌석을 잡은 사람에게는
 * (여기서는 300초로) 연장해서 구매 도중에 끊기지 않게 한다. compose Redis와 MySQL에서 실행하고,
 * compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
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
        redissonClient.<String>getScoredSortedSet(QueueService.activeKey(eventId)).add(System.currentTimeMillis() + 900_000, token); // 이미 더 길다

        assertThat(queueService.extendPassesOfHolder(u, eventId)).isZero();

        assertThat(expiresInSeconds(token)).isBetween(895.0, 901.0);
    }

    @Test
    void aPassThatHasRunOutIsNotRevivedAndSomeoneWaitingHasNothingToExtend() {
        long expired = enterAndAdmit(user());
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(QueueService.activeKey(eventId));
        active.add(System.currentTimeMillis() - 1_000, tokens.get(0)); // 1초 전에 끝났다
        long notAdmitted = user();

        assertThat(queueService.extendPassesOfHolder(expired, eventId)).isZero();
        assertThat(queueService.isPassTokenValid(eventId, tokens.get(0))).isFalse();
        assertThat(queueService.extendPassesOfHolder(notAdmitted, eventId)).isZero(); // 대기열에 선 적 없음
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
