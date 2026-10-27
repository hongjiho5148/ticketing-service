package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** With queue.release-slot-on-purchase=false a paying buyer keeps their slot until the pass token expires, as before. */
@SpringBootTest(properties = {
    "queue.active-capacity=2",
    "queue.admission-interval-ms=3600000",
    "queue.release-slot-on-purchase=false"
})
@Transactional
class QueueSlotReleaseDisabledTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 6_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private String token;

    @AfterEach
    void cleanUpRedis() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        if (token != null) {
            redissonClient.getKeys().delete("queue:event:" + token);
        }
    }

    @Test
    void theSlotIsKeptWhenReleasingIsSwitchedOff() {
        long buyer = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        QueueEnterResponse response = queueService.enter(buyer, eventId);
        token = response.queueToken();
        sweeper.admitForEvent(eventId);

        assertThat(queueService.releaseSlotsOfBuyer(buyer, eventId)).isZero();

        assertThat(queueService.isPassTokenValid(eventId, token)).isTrue();
    }
}
