package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 예약된 폴링 한 번(drain)이 한 묶음(100개)만 보내고 쉬는 것이 아니라, 밀려 있는 동안 이어서 처리하는지 검사한다. 일꾼은 하나다.
 * 폴링 한 번 안의 순서 규칙(앞의 이벤트가 실패하면 뒤의 것은 보내지 않는다)은 OutboxRelayTest가 검사하고,
 * 여기서는 그 규칙이 여러 묶음에 걸쳐서도 지켜지는지 본다. 실제 MySQL을 쓰고 compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-",
    "outbox.kick-after-commit=false",
    // 이 클래스는 일꾼 하나의 보장("실패하면 뒤의 것은 보내지 않는다")을 검사한다. 일꾼이 여럿이면 다른 일꾼이 뒤쪽 묶음을
    // 보내므로 이 보장은 일꾼 사이에서는 성립하지 않는다(OutboxWorkersTest가 그때의 보장을 검사한다).
    "outbox.relay-workers=1",
    "outbox.topic-pattern=outbox-drain-%"
})
class OutboxDrainTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxEventRepository repository;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    private String topic;

    @BeforeEach
    void setUp() {
        topic = "outbox-drain-" + SEQ.incrementAndGet();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll(mine());
    }

    private List<OutboxEvent> mine() {
        return repository.findAll().stream().filter(e -> e.getTopic().equals(topic)).toList();
    }

    /** 키에 순번을 채워 넣어(e000, e001, ...) 기록된 순서가 키 순서와 같게 한다. */
    private String save(int n) {
        String key = String.format("e%03d", n);
        repository.save(new OutboxEvent(topic, key, "{\"key\":\"" + key + "\"}"));
        return key;
    }

    @Test
    void aBacklogLargerThanOneBatchIsDrainedByASingleTick() {
        int total = OutboxEventRepository.CLAIM_BATCH * 2 + 30; // 꽉 찬 묶음 둘과 덜 찬 묶음 하나
        for (int i = 0; i < total; i++) {
            save(i);
        }

        relay.drain(); // 예약된 폴링이 한 번 돈 것과 같다

        assertThat(mine()).hasSize(total).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
        for (int i = 0; i < total; i++) {
            verify(kafkaTemplate, times(1)).send(eq(topic), eq(String.format("e%03d", i)), anyString());
        }
    }

    @Test
    void aTickWithNothingToSendReturnsImmediately() {
        relay.drain();

        verify(kafkaTemplate, never()).send(eq(topic), anyString(), anyString());
    }

    @Test
    void aFailureInTheMiddleOfTheBacklogStopsTheDrainAndNothingAfterItIsSent() {
        int total = OutboxEventRepository.CLAIM_BATCH * 2 + 50;
        for (int i = 0; i < total; i++) {
            save(i);
        }
        int failing = OutboxEventRepository.CLAIM_BATCH + 20; // 두 번째 묶음의 중간
        String failingKey = String.format("e%03d", failing);
        when(kafkaTemplate.send(eq(topic), eq(failingKey), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.drain();

        // 실패한 이벤트까지는 나갔고, 그 뒤의 이벤트는 하나도 보내지 않았다. 앞지르기가 없다.
        assertThat(mine())
                .filteredOn(e -> e.getEventKey().compareTo(failingKey) < 0)
                .hasSize(failing)
                .allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
        assertThat(mine())
                .filteredOn(e -> e.getEventKey().compareTo(failingKey) >= 0)
                .hasSize(total - failing)
                .allSatisfy(e -> assertThat(e.getSentAt()).isNull());
        for (int i = failing + 1; i < total; i++) {
            verify(kafkaTemplate, never()).send(eq(topic), eq(String.format("e%03d", i)), anyString());
        }
        // 실패한 이벤트를 한 번 시도하고 멈췄다. 같은 틱 안에서 계속 두드리지 않는다.
        verify(kafkaTemplate, times(1)).send(eq(topic), eq(failingKey), anyString());
    }
}
