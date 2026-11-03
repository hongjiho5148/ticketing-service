package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * Prometheus가 대기열에 대해 보는 것이다. 기다리는 사람과 슬롯을 쥔 사람이 몇 명인지, 몇 명이 들어와 입장하고
 * 슬롯을 돌려줬는지. compose Redis에 다른 대기열이 있을 수 있어서 숫자는 전후로 비교한다.
 * compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
 */
@SpringBootTest(properties = {"queue.active-capacity=2", "queue.admission-interval-ms=3600000"})
@Transactional
class QueueMetricsTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;
    @Autowired private MeterRegistry meters;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 3_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUpRedis() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private double count(String name) {
        return meters.get(name).counter().count();
    }

    @Test
    void peopleInTheQueueAreCountedAsWaitingThenActiveAndTheFlowCountersMove() {
        double waitingBefore = gauge("queue_waiting_users");
        double activeBefore = gauge("queue_active_users");
        double enteredBefore = count("queue_entered_total");
        double admittedBefore = count("queue_admitted_total");
        double releasedBefore = count("queue_slots_released_total");
        long buyer = 9_300_000_000L + SEQ.incrementAndGet() % 1_000_000;

        tokens.add(queueService.enter(buyer, eventId).queueToken());
        tokens.add(queueService.enter(buyer + 1, eventId).queueToken());
        tokens.add(queueService.enter(buyer + 2, eventId).queueToken());
        assertThat(count("queue_entered_total")).isEqualTo(enteredBefore + 3);
        assertThat(gauge("queue_waiting_users")).isEqualTo(waitingBefore + 3);

        sweeper.admitForEvent(eventId); // 용량 2: 둘은 들어가고 하나는 계속 기다린다
        assertThat(count("queue_admitted_total")).isEqualTo(admittedBefore + 2);
        assertThat(gauge("queue_waiting_users")).isEqualTo(waitingBefore + 1);
        assertThat(gauge("queue_active_users")).isEqualTo(activeBefore + 2);

        queueService.releaseSlotsOfBuyer(buyer, eventId);
        assertThat(count("queue_slots_released_total")).isEqualTo(releasedBefore + 1);
        assertThat(gauge("queue_active_users")).isEqualTo(activeBefore + 1);
    }
}
