package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.ticketing.backend.eventclient.EventServiceClient;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Joining the queue must not hold a database connection while it waits on event-service. The test class is deliberately not
 * @Transactional (a test-wide transaction would be active everywhere and prove nothing), so it can see whether
 * QueueService.enter itself opened one around the remote call, and that the history row still ends up committed. Against
 * the compose Redis and MySQL; run inside the compose network with DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {"queue.admission-interval-ms=3600000"})
class QueueEnterTransactionTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private WaitingQueueRepository waitingQueueRepository;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 1_500_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private String queueToken;

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId,
                QueueService.seenKey(eventId));
        if (queueToken != null) {
            redissonClient.getKeys().delete("queue:event:" + queueToken);
            waitingQueueRepository.findByQueueToken(queueToken).ifPresent(waitingQueueRepository::delete);
        }
    }

    @Test
    void noTransactionIsOpenWhileEventServiceIsBeingAskedAndTheHistoryRowIsStillSaved() {
        AtomicBoolean transactionDuringRemoteCall = new AtomicBoolean(true);
        when(eventServiceClient.getEvent(anyLong())).thenAnswer(invocation -> {
            transactionDuringRemoteCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });

        queueToken = queueService.enter(9_950_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId).queueToken();

        assertThat(transactionDuringRemoteCall).isFalse();
        assertThat(waitingQueueRepository.findByQueueToken(queueToken)).isPresent();
    }
}
