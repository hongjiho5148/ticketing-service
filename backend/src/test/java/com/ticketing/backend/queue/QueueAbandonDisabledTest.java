package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** queue.abandon-grace-ms=0 switches the clean-up off: the line behaves as it did before. */
@SpringBootTest(properties = {"queue.active-capacity=2", "queue.admission-interval-ms=3600000", "queue.abandon-grace-ms=0"})
@Transactional
class QueueAbandonDisabledTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 4_500_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId,
                QueueService.seenKey(eventId));
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    @Test
    void nobodyIsTakenOutWhenTheGracePeriodIsZero() {
        String a = queueService.enter(9_500_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId).queueToken();
        tokens.add(a);
        redissonClient.getScoredSortedSet(QueueService.seenKey(eventId)).add(System.currentTimeMillis() - 3_600_000, a);

        sweeper.admitForEvent(eventId);

        assertThat(queueService.isPassTokenValid(eventId, a)).isTrue(); // admitted like anyone else
    }
}
