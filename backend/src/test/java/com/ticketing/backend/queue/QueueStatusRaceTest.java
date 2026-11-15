package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입장하는 순간의 사람이 "대기열에 없음"으로 보이면 안 된다. 입장 처리는 입장 목록에 넣고 대기 목록에서 빼는 두 단계라서, 상태를
 * 입장 목록부터 읽으면 두 읽기 사이에 입장 처리가 끝났을 때 둘 다에서 못 찾고 404가 나갔다(화면에는 "줄에서 빠졌어요").
 * 한 번의 입장 처리가 사람을 하나씩 옮기는 동안 여러 스레드가 계속 상태를 묻는 부하 테스트다. 틈이 있으면 확률적으로 잡히는
 * 테스트라서 결정적이지는 않지만, 읽는 순서를 예전(입장 목록 먼저)으로 되돌려 한 번 돌렸을 때 실패하는 것을 확인했다. 정원을 사람
 * 수보다 크게 잡아 한 번에 모두 입장시킨다. compose Redis와 MySQL에서 실행하고(데이터베이스 부분은 롤백), compose 네트워크 안에서
 * DB_HOST=mysql REDIS_HOST=redis로 실행한다.
 */
@SpringBootTest(properties = {"queue.active-capacity=1000", "queue.admission-interval-ms=3600000"})
@Transactional
class QueueStatusRaceTest {

    private static final long BASE_EVENT = 7_400_000_000L;
    private static final int ROUNDS = 8;
    private static final int PEOPLE = 300;

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (int round = 0; round < ROUNDS; round++) {
            long event = BASE_EVENT + round;
            redissonClient.getKeys().delete("queue:waiting:" + event, "queue:active:" + event, "queue:seq:" + event, "queue:seen:" + event);
            for (int i = 0; i < PEOPLE; i++) {
                redissonClient.getKeys().delete("queue:place:" + event + ":" + (9_400_000_000L + i));
            }
        }
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    @Test
    void aPersonBeingAdmittedIsNeverReportedAsGone() throws Exception {
        AtomicInteger spurious = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        ExecutorService pollers = Executors.newFixedThreadPool(8);

        for (int round = 0; round < ROUNDS; round++) {
            long event = BASE_EVENT + round;
            List<String> inLine = new ArrayList<>();
            for (int i = 0; i < PEOPLE; i++) {
                String token = queueService.enter(9_400_000_000L + i, event).queueToken();
                inLine.add(token);
                tokens.add(token);
            }
            AtomicBoolean stop = new AtomicBoolean();
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                running.add(pollers.submit(() -> {
                    while (!stop.get()) {
                        String token = inLine.get(ThreadLocalRandom.current().nextInt(inLine.size()));
                        try {
                            queueService.status(token);
                            polls.incrementAndGet();
                        } catch (ApiException e) {
                            if (e.getErrorCode() == ErrorCode.QUEUE_TOKEN_NOT_FOUND) {
                                spurious.incrementAndGet();
                            }
                        }
                    }
                }));
            }
            Thread.sleep(50); // 묻는 스레드들이 돌기 시작한 뒤에 입장시킨다
            sweeper.admitForEvent(event); // 정원이 커서 모두 한 번에 입장한다
            Thread.sleep(50);
            stop.set(true);
            for (var future : running) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        pollers.shutdown();

        assertThat(polls.get()).as("polls that completed").isGreaterThan(1_000);
        assertThat(spurious.get()).as("people reported as gone while being admitted").isZero();
    }
}
