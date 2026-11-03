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
 * 브로커 이쪽의 전체 경로다. Kafka에 발행한 reservation.confirmed 메시지가 리스너에 닿고, 리스너가 구매자의 슬롯을 돌려준다.
 * compose 스택의 실제 Kafka, Redis, MySQL을 쓴다. 리스너는 자기 스레드에서 돌기 때문에 읽는 행이 커밋되어 있어야 한다.
 * 이 클래스는 @Transactional이 아니고 자기가 만든 것을 지운다. compose 네트워크 안에서
 * DB_HOST=mysql REDIS_HOST=redis KAFKA_BOOTSTRAP_SERVERS=kafka:9092로 실행한다.
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
        sweeper.admitForEvent(eventId); // 타이머는 꺼 두었으므로 둘을 손으로 입장시킨다
        assertThat(passValid(buyerToken)).isTrue();
        assertThat(passValid(otherToken)).isTrue();

        confirmed(buyer);

        awaitUntil(() -> !passValid(buyerToken));
        assertThat(passValid(otherToken)).isTrue();

        // 같은 메시지가 두 번째로 전달돼도(최소 한 번 전달) 아무것도 바뀌지 않는다
        confirmed(buyer);
        Thread.sleep(1500);
        assertThat(passValid(otherToken)).isTrue();
    }

    /**
     * 스위퍼는 자기 자신에게 호출하는 @Transactional 메서드로 사람들을 입장시켰고, Spring은 이것을 가로채지 못해서 실행 중인
     * 서비스에서 이력 행이 WAITING으로 남았다. 프록시를 거치지 않은 빈으로 입장시키면(운영과 똑같이 감싸는 트랜잭션 없이)
     * 이제 PASSED가 되어야 하고 슬롯도 여전히 돌아온다.
     */
    @Test
    void theSlotIsHandedBackAndTheAdmissionMarkedTheHistoryRowPassed() throws Exception {
        long buyer = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        String buyerToken = enter(buyer);
        AopTestUtils.<QueueAdmissionSweeper>getUltimateTargetObject(sweeper).admitForEvent(eventId);
        assertThat(passValid(buyerToken)).isTrue();
        assertThat(waitingQueueRepository.findByQueueToken(buyerToken).orElseThrow().getStatus())
                .isEqualTo(QueueEntryStatus.PASSED);

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

        // 나쁜 메시지는 재시도되고 데드 레터로 간다(약 3초). 그 뒤에 있던 좋은 메시지는 그다음에 처리된다
        for (int i = 0; i < 200 && passValid(buyerToken); i++) {
            Thread.sleep(100);
        }
        assertThat(passValid(buyerToken)).isFalse();
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet("queue:active:" + eventId);
        assertThat(active.contains(buyerToken)).isFalse();
    }
}
