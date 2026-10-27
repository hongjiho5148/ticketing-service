package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * What Prometheus sees of the outbox: how many events are waiting, how long the oldest has waited, and how many went out
 * or failed. Against the compose MySQL (rolled back, so the table's own rows are counted before and after); run inside
 * the compose network with DB_HOST=mysql.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-",
    "outbox.topic-pattern=outbox-metrics-%"
})
@Transactional
class OutboxMetricsTest {

    @Autowired private OutboxRecorder recorder;
    @Autowired private OutboxRelay relay;
    @Autowired private MeterRegistry meters;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private double count(String name) {
        return meters.get(name).counter().count();
    }

    @Test
    void waitingEventsAreCountedAndTheOldestOneHasAnAge() {
        double before = gauge("outbox_unsent_events");

        recorder.record("outbox-metrics-a", "1", Map.of("k", 1));
        recorder.record("outbox-metrics-a", "2", Map.of("k", 2));

        assertThat(gauge("outbox_unsent_events")).isEqualTo(before + 2);
        assertThat(gauge("outbox_oldest_unsent_age_seconds")).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void sentAndFailedAttemptsAreCounted() {
        recorder.record("outbox-metrics-b", "1", Map.of("k", 1));
        double sentBefore = count("outbox_events_sent_total");
        double failedBefore = count("outbox_send_failures_total");

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        relay.relayOnce();
        assertThat(count("outbox_send_failures_total")).isEqualTo(failedBefore + 1);
        assertThat(count("outbox_events_sent_total")).isEqualTo(sentBefore);

        reset(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        relay.relayOnce();
        assertThat(count("outbox_events_sent_total")).isEqualTo(sentBefore + 1);
    }
}
