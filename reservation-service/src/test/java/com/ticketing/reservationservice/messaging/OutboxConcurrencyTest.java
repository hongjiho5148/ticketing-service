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
 * Several relays working the same outbox, and the nudge after a commit. These need rows that are really committed (the
 * relays run on other threads and connections), so the class is NOT @Transactional and cleans up its own rows. The relay
 * is restricted to this test's topic prefix, so any real rows in the table are never touched. Compose MySQL; run inside
 * the compose network with DB_HOST=mysql.
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
            Thread.sleep(2); // a real send takes a moment, which is when overlapping relays would collide
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
        // a poll that claimed this row and then died: its lease is already over
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
        // the mark-as-sent follows the send by a moment
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
            // rolled back together with the event row
        }

        Thread.sleep(500);
        verify(kafkaTemplate, never()).send(eq(topic), eq("rolled-back"), anyString());
        assertThat(mine()).isEmpty();
    }
}
