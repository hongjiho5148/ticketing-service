package com.ticketing.reservationservice.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * How far behind the outbox is. Both numbers are read from the table when Prometheus scrapes (every few seconds), so they
 * are right even after a restart and for rows another instance wrote. The age of the oldest waiting event is the one to
 * alert on: a few seconds is normal, minutes means Kafka (or the relay) is not delivering.
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxEventRepository repository;

    public OutboxMetrics(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox_unsent_events", this, m -> m.unsent())
                .description("Events recorded in the outbox that Kafka has not acknowledged yet")
                .register(registry);
        Gauge.builder("outbox_oldest_unsent_age_seconds", this, m -> m.oldestAgeSeconds())
                .description("How long the oldest unsent outbox event has been waiting (0 when there is none)")
                .baseUnit("seconds")
                .register(registry);
    }

    private double unsent() {
        try {
            return repository.countBySentAtIsNull();
        } catch (RuntimeException e) {
            return Double.NaN; // the database is not answering: report "unknown", not zero
        }
    }

    private double oldestAgeSeconds() {
        try {
            return repository.findFirstBySentAtIsNullOrderByIdAsc()
                    .map(event -> (double) Duration.between(event.getCreatedAt(), LocalDateTime.now()).toSeconds())
                    .orElse(0.0);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
