package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 여러 릴레이가 같은 아웃박스를 처리하는 경우와 커밋 뒤의 깨우기다. 실제로 커밋된 행이 필요하므로(릴레이는 다른
 * 스레드와 커넥션에서 돈다) 이 클래스는 @Transactional이 아니고 자기가 만든 행을 직접 정리한다. 릴레이는 이 테스트의
 * 토픽 접두사로 제한되어, 테이블에 있는 실제 행은 건드리지 않는다. compose MySQL을 쓰고,
 * compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-",
    "outbox.topic-pattern=outbox-conc-%"
})
class OutboxConcurrencyTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxRecorder recorder;
    @Autowired private OutboxEventRepository repository;
    @Autowired private OutboxClaimer claimer;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    private final Map<String, AtomicInteger> sends = new ConcurrentHashMap<>();
    private String topic;

    @BeforeEach
    void setUp() {
        topic = "outbox-conc-" + SEQ.incrementAndGet();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sends.computeIfAbsent(inv.getArgument(1), k -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(2); // 실제 전송은 잠깐 걸리고, 그때 겹치는 릴레이들이 부딪힌다
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

    private void save(String key) {
        repository.save(new OutboxEvent(topic, key, "{\"key\":\"" + key + "\"}"));
    }

    @Test
    void severalRelaysWorkingAtOnceSendEveryEventExactlyOnce() throws Exception {
        for (int i = 0; i < 120; i++) {
            save("e" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> relays = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            relays.add(pool.submit(() -> {
                for (int round = 0; round < 15; round++) {
                    relay.relayOnce();
                }
            }));
        }
        for (Future<?> f : relays) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(sends).hasSize(120);
        assertThat(sends.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(mine()).hasSize(120).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
    }

    @Test
    void rowsAnotherPollHoldsALiveLeaseOnAreLeftAloneButAnExpiredLeaseIsTakenOver() {
        save("leased");
        LocalDateTime now = LocalDateTime.now();
        assertThat(claimer.claim("another-poll", now, now.plusSeconds(30), "outbox-conc-%")).isEqualTo(1);
        save("abandoned");
        // 이 행을 가져갔다가 죽은 폴링이다. 그 임대는 이미 끝났다
        assertThat(claimer.claim("dead-poll", now, now.minusSeconds(1), "outbox-conc-%")).isEqualTo(1);

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(sends).containsOnlyKeys("abandoned");
        assertThat(mine()).filteredOn(e -> e.getEventKey().equals("leased")).allSatisfy(e -> assertThat(e.getSentAt()).isNull());
    }

    @Test
    void anEventRecordedInACommittedTransactionIsSentWithoutWaitingForThePoll() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> recorder.record(topic, "nudged", Map.of("k", "nudged")));

        verify(kafkaTemplate, timeout(5000)).send(eq(topic), eq("nudged"), anyString());
        // 전송 완료 표시는 전송보다 조금 늦게 따라온다
        for (int i = 0; i < 50 && mine().stream().anyMatch(e -> e.getSentAt() == null); i++) {
            Thread.sleep(100);
        }
        assertThat(mine()).hasSize(1).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
    }

    @Test
    void anEventThatWasRolledBackIsNeverNudgedOut() throws Exception {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                recorder.record(topic, "rolled-back", Map.of("k", "rolled-back"));
                throw new IllegalStateException("the change failed after the event was recorded");
            });
        } catch (IllegalStateException expected) {
            // 이벤트 행과 함께 롤백됐다
        }

        Thread.sleep(500);
        verify(kafkaTemplate, never()).send(eq(topic), eq("rolled-back"), anyString());
        assertThat(mine()).isEmpty();
    }
}
