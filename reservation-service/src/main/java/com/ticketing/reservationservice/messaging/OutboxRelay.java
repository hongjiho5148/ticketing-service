package com.ticketing.reservationservice.messaging;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes what OutboxRecorder stored. It runs outside any database transaction: the broker is a network call, and a
 * transaction around it would hold a pooled connection while waiting (the same trap the reservation path avoids).
 *
 * <p>Delivery is at-least-once: an event is marked sent only after the broker acknowledged it, so a crash between the
 * two sends it again, and a second instance polling the same rows can too. Consumers must therefore tolerate
 * duplicates. Order is kept per poll - the first failure stops the batch so later events never overtake an earlier one.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;
    private final long retentionHours;
    private long lastWarnAtMs;

    public OutboxRelay(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${outbox.send-timeout-ms:5000}") long sendTimeoutMs,
            @Value("${outbox.retention-hours:24}") long retentionHours) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
        this.retentionHours = retentionHours;
    }

    @Scheduled(
            fixedDelayString = "${outbox.relay-interval-ms:500}",
            initialDelayString = "${outbox.relay-initial-delay-ms:1000}")
    public void relay() {
        relayOnce();
    }

    /** Sends one batch of unsent events in order; returns how many reached the broker. */
    public int relayOnce() {
        List<OutboxEvent> batch = repository.findTop100BySentAtIsNullOrderByIdAsc();
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate
                        .send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Kafka is down or slow. Nothing is lost - the rows stay unsent and the next poll starts from this one.
                warnThrottled(event, batch.size() - sent, e);
                break;
            }
            repository.markSent(event.getId(), LocalDateTime.now());
            sent++;
        }
        return sent;
    }

    /** The poll runs twice a second, so an outage would otherwise write two warnings a second; one every 30s is enough. */
    private void warnThrottled(OutboxEvent event, int waiting, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAtMs >= 30_000) {
            lastWarnAtMs = now;
            log.warn("Outbox event {} ({}) not delivered, {} waiting (retrying every poll): {}",
                    event.getId(), event.getTopic(), waiting, e.getMessage());
        }
    }

    /** Sent rows are only kept for a while (to look at what went out); unsent ones are never deleted. */
    @Scheduled(cron = "${outbox.purge-cron:0 0 * * * *}")
    public void purgeSent() {
        int deleted = repository.deleteSentBefore(LocalDateTime.now().minusHours(retentionHours));
        if (deleted > 0) {
            log.info("Purged {} sent outbox events", deleted);
        }
    }
}
