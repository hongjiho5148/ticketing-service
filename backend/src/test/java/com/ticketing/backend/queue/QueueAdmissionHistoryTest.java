package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 입장 스윕은 이력 행을 PASSED로 남겨야 한다. 이 클래스는 일부러 @Transactional로 하지 않았다. 테스트 전체 트랜잭션 안에서는
 * 스위퍼에 트랜잭션이 빠져 있어도 보이지 않는다(엔티티 변경이 테스트 자신의 것과 함께 flush된다). 그래서 실행 중인
 * 서비스에서 행이 WAITING으로 남는 것을 알아채지 못했다. 여기서는 모든 쓰기를 스위퍼가 직접 커밋해야 하고,
 * 끝나면 행을 지운다. ReservationConfirmedListenerTest와 똑같이 구성해서 둘이 Spring 컨텍스트 하나를 공유한다.
 * 컨텍스트가 하나 늘 때마다 같은 그룹에 Kafka 컨슈머가 따로 붙고, 그것이 일으키는 리밸런스가 그 테스트의 리스너를 지연시킨다.
 * compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis KAFKA_BOOTSTRAP_SERVERS=kafka:9092로 실행한다.
 */
@SpringBootTest(properties = {"queue.active-capacity=5", "queue.admission-interval-ms=3600000"})
class QueueAdmissionHistoryTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private WaitingQueueRepository waitingQueueRepository;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 6_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        tokens.forEach(t -> {
            redissonClient.getKeys().delete("queue:event:" + t);
            waitingQueueRepository.findByQueueToken(t).ifPresent(waitingQueueRepository::delete);
        });
    }

    private String enter() {
        QueueEnterResponse response = queueService.enter(9_200_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId);
        tokens.add(response.queueToken());
        return response.queueToken();
    }

    private WaitingQueue row(String token) {
        return waitingQueueRepository.findByQueueToken(token).orElseThrow();
    }

    @Test
    void theScheduledSweepSavesPassedOnlyForThoseItLetIn() {
        List<String> inLine = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            inLine.add(enter());
        }
        String first = inLine.get(0);
        String last = inLine.get(5); // 용량 5라서 이 사람은 계속 기다린다

        sweeper.admitWaitingUsers(); // 타이머가 호출하는 것과 같은 진입점

        inLine.subList(0, 5).forEach(t -> assertThat(row(t).getStatus()).isEqualTo(QueueEntryStatus.PASSED));
        assertThat(row(first).getPassedAt()).isNotNull();
        assertThat(row(last).getStatus()).isEqualTo(QueueEntryStatus.WAITING);
        assertThat(row(last).getPassedAt()).isNull();
        assertThat(queueService.isPassTokenValid(eventId, first)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, last)).isFalse();
    }

    @Test
    void thePersonWhoWaitedIsMarkedPassedOnTheNextSweepOnceASlotIsFree() {
        List<String> inLine = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            inLine.add(enter());
        }
        sweeper.admitWaitingUsers();
        redissonClient.getScoredSortedSet("queue:active:" + eventId).remove(inLine.get(0)); // 슬롯이 하나 빈다

        sweeper.admitWaitingUsers();

        assertThat(row(inLine.get(5)).getStatus()).isEqualTo(QueueEntryStatus.PASSED);
        assertThat(row(inLine.get(5)).getPassedAt()).isNotNull();
    }
}
