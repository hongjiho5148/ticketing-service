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
 * 대기열에 들어갈 때 event-service를 기다리는 동안 데이터베이스 커넥션을 붙잡고 있으면 안 된다. 이 테스트 클래스는 일부러
 * @Transactional로 하지 않았다(테스트 전체 트랜잭션은 어디서든 활성이라 아무것도 증명하지 못한다). 그래서
 * QueueService.enter 자체가 원격 호출을 감싸는 트랜잭션을 열었는지, 그리고 이력 행이 여전히 커밋되는지 볼 수 있다.
 * compose Redis와 MySQL에서 실행하고, compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
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
