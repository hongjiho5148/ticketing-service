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
 * The admission sweep must leave the history rows PASSED. This class is deliberately NOT @Transactional: inside a
 * test-wide transaction a missing transaction in the sweeper is invisible (the entity change is flushed with the test's
 * own), which is how rows staying WAITING in the running service went unnoticed. Here every write must be committed by
 * the sweeper itself, and the rows are deleted afterwards. Configured exactly like ReservationConfirmedListenerTest so
 * both share one Spring context: every extra context brings its own Kafka consumers into the same group, and the
 * rebalances they cause delay that test's listener. Run inside the compose network with DB_HOST=mysql REDIS_HOST=redis
 * KAFKA_BOOTSTRAP_SERVERS=kafka:9092.
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
        String last = inLine.get(5); // capacity 5, so this one keeps waiting

        sweeper.admitWaitingUsers(); // the same entry point the timer calls

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
        redissonClient.getScoredSortedSet("queue:active:" + eventId).remove(inLine.get(0)); // a slot frees up

        sweeper.admitWaitingUsers();

        assertThat(row(inLine.get(5)).getStatus()).isEqualTo(QueueEntryStatus.PASSED);
        assertThat(row(inLine.get(5)).getPassedAt()).isNotNull();
    }
}
