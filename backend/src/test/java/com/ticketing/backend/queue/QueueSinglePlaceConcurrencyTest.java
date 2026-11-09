package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 같은 사람의 입장 요청이 동시에 여러 개 들어와도(개발 모드가 효과를 두 번 실행하거나 더블 클릭) 줄에는 자리 하나만 생긴다.
 * 확인하고 만드는 사이에 다른 요청이 끼어들 수 있는지를 보는 것이라, 실제로 겹쳐서 실행하려고 이 클래스는 일부러
 * @Transactional로 하지 않았다. 각 스레드가 이력 행을 직접 커밋하므로 끝나면 지운다. compose Redis와 MySQL에서 실행하고,
 * compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
 */
@SpringBootTest(properties = {"queue.active-capacity=2", "queue.admission-interval-ms=3600000"})
class QueueSinglePlaceConcurrencyTest {

    private static final long EVENT_ID = 7_200_000_001L;
    private static final long USER_ID = 9_200_000_001L;

    @Autowired private QueueService queueService;
    @Autowired private RedissonClient redissonClient;
    @Autowired private WaitingQueueRepository waitingQueueRepository;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete(
                "queue:waiting:" + EVENT_ID, "queue:active:" + EVENT_ID, "queue:seq:" + EVENT_ID, "queue:seen:" + EVENT_ID,
                "queue:place:" + EVENT_ID + ":" + USER_ID);
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
        waitingQueueRepository.deleteAll(waitingQueueRepository.findByUserIdAndEventId(USER_ID, EVENT_ID));
    }

    @Test
    void manyRequestsAtOnceFromOnePersonMakeOnePlace() throws Exception {
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<QueueEnterResponse>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return queueService.enter(USER_ID, EVENT_ID);
            }));
        }
        go.countDown();
        List<QueueEnterResponse> responses = new ArrayList<>();
        for (Future<QueueEnterResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        responses.forEach(r -> tokens.add(r.queueToken()));

        Set<String> distinct = responses.stream().map(QueueEnterResponse::queueToken).collect(Collectors.toSet());
        assertThat(distinct).hasSize(1);
        assertThat(redissonClient.getScoredSortedSet("queue:waiting:" + EVENT_ID).size()).isEqualTo(1);
        assertThat(waitingQueueRepository.findByUserIdAndEventId(USER_ID, EVENT_ID)).hasSize(1);
    }
}
