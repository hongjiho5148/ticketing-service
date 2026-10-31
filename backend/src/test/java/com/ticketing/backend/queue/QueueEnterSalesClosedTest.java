package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.eventclient.dto.EventExistsResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nobody joins the line for a show whose booking is over, and nothing is left in Redis for them. Against the compose Redis
 * and MySQL; run inside the compose network with DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {"queue.admission-interval-ms=3600000"})
@Transactional
class QueueEnterSalesClosedTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 1_700_000_000L + SEQ.incrementAndGet() % 1_000_000;

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:seq:" + eventId, QueueService.seenKey(eventId));
    }

    @Test
    void joiningTheLineOfAShowWhoseSalesAreOverIsRefused() {
        when(eventServiceClient.getEvent(anyLong())).thenReturn(new EventExistsResponse(eventId, "마감된 공연", true));

        assertThatThrownBy(() -> queueService.enter(9_960_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SALES_CLOSED));
        assertThat(redissonClient.getScoredSortedSet("queue:waiting:" + eventId).size()).isZero();
    }

    @Test
    void aShowStillOnSaleTakesPeopleIn() {
        when(eventServiceClient.getEvent(anyLong())).thenReturn(new EventExistsResponse(eventId, "판매 중인 공연", false));

        assertThat(queueService.enter(9_960_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId).queueToken()).isNotBlank();
    }
}
