package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 릴레이 일꾼이 여럿(outbox.relay-workers=4)일 때, 정말 동시에 보내면서도 모든 이벤트를 정확히 한 번씩만 보내는지 검사한다.
 * 실제 MySQL을 쓰고 compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-",
    "outbox.kick-after-commit=false",
    "outbox.relay-workers=4",
    "outbox.topic-pattern=outbox-workers-%"
})
class OutboxWorkersTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxEventRepository repository;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    private final Map<String, AtomicInteger> sends = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private String topic;

    @BeforeEach
    void setUp() {
        topic = "outbox-workers-" + SEQ.incrementAndGet();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            sends.computeIfAbsent(inv.getArgument(1), k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(5); // 브로커 확인을 기다리는 시간. 일꾼이 여럿이면 이 동안 서로 겹친다.
            } finally {
                inFlight.decrementAndGet();
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll(mine());
    }

    private List<OutboxEvent> mine() {
        return repository.findAll().stream().filter(e -> e.getTopic().equals(topic)).toList();
    }

    @Test
    void workersSendAtTheSameTimeAndEveryEventGoesOutExactlyOnce() {
        int total = 600;
        for (int i = 0; i < total; i++) {
            repository.save(new OutboxEvent(topic, "e" + i, "{\"key\":\"e" + i + "\"}"));
        }

        relay.drain();

        assertThat(sends).hasSize(total);
        assertThat(sends.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(mine()).hasSize(total).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
        // 일꾼이 하나였다면 동시에 진행 중인 전송은 1을 넘을 수 없다.
        assertThat(maxInFlight.get()).as("동시에 진행 중이던 전송의 최대 개수").isGreaterThanOrEqualTo(2);
    }

    @Test
    void whenTheBrokerIsDownNothingIsLostAndEachWorkerGivesUpAfterOneFailedSend() {
        int total = 400;
        for (int i = 0; i < total; i++) {
            repository.save(new OutboxEvent(topic, "e" + i, "{\"key\":\"e" + i + "\"}"));
        }
        AtomicInteger attempts = new AtomicInteger();
        reset(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        });

        relay.drain();

        // 아무것도 전송 완료로 표시되지 않았다. 잃은 것이 없고, 브로커가 돌아오면 다음 폴링이 처음부터 보낸다.
        assertThat(mine()).hasSize(total).allSatisfy(e -> assertThat(e.getSentAt()).isNull());
        // 일꾼마다 첫 실패에서 멈췄다. 죽은 브로커를 쉬지 않고 두드리지 않는다(일꾼 4개면 시도는 최대 4번).
        assertThat(attempts.get()).as("실패한 전송 시도 횟수").isBetween(1, 4);
    }
}
