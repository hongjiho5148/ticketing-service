package com.ticketing.reservationservice.messaging;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
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
 * <p>Each poll first <b>claims</b> a batch of unsent rows with a short lease (one atomic UPDATE), so several instances -
 * or the poll and a {@link #kick()} running at the same moment - never send the same row. If a claimant dies mid-send the
 * lease runs out and another poll takes the rows over.
 *
 * <p>Delivery is still at-least-once: a row is marked sent only after the broker acknowledged it, so a crash between the
 * two (or a lease that expires while a slow send is still in flight) can send it again. Consumers must tolerate
 * duplicates. Order is kept within a poll - the first failure stops the batch and gives the rest back, so later events
 * never overtake an earlier one. Across instances, rows claimed by different polls may go out in either order, which
 * only matters for events sharing a key (today every event has its own).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final OutboxClaimer claimer;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;
    private final long retentionHours;
    private final long leaseMs;
    private final String topicPattern;
    private long lastWarnAtMs;

    /** At most one extra poll waits behind a running one; more requests are dropped, since that poll sees everything. */
    private final ExecutorService kicker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());

    public OutboxRelay(
            OutboxEventRepository repository,
            OutboxClaimer claimer,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${outbox.send-timeout-ms:5000}") long sendTimeoutMs,
            @Value("${outbox.retention-hours:24}") long retentionHours,
            @Value("${outbox.lease-ms:30000}") long leaseMs,
            @Value("${outbox.topic-pattern:%}") String topicPattern) {
        this.repository = repository;
        this.claimer = claimer;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
        this.retentionHours = retentionHours;
        this.leaseMs = leaseMs;
        this.topicPattern = topicPattern;
    }

    @Scheduled(
            fixedDelayString = "${outbox.relay-interval-ms:500}",
            initialDelayString = "${outbox.relay-initial-delay-ms:1000}")
    public void relay() {
        relayOnce();
    }

    /**
     * Asks for a poll right now instead of waiting for the next tick; called after an event is committed so it leaves
     * within milliseconds. Returns at once - the poll runs on its own thread. The scheduled poll stays as the safety
     * net for anything this misses (a failed send, a restart, an event recorded by another instance).
     */
    public void kick() {
        kicker.execute(this::relayOnce);
    }

    @PreDestroy
    void stopKicker() {
        kicker.shutdown();
    }

    /** Sends one batch of unsent events in order; returns how many reached the broker. */
    public int relayOnce() {
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        if (claimer.claim(token, now, now.plus(Duration.ofMillis(leaseMs)), topicPattern) == 0) {
            return 0;
        }
        List<OutboxEvent> batch = repository.findByClaimedByAndSentAtIsNullOrderByIdAsc(token);
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate
                        .send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                repository.release(token);
                break;
            } catch (Exception e) {
                // Kafka is down or slow. Nothing is lost - the rows go back to unsent and the next poll starts from this one.
                repository.release(token);
                warnThrottled(batch.get(0), batch.size() - sent, e);
                break;
            }
            repository.markSent(event.getId(), LocalDateTime.now());
            sent++;
        }
        return sent;
    }

    /**
     * The poll runs twice a second, so an outage would otherwise write two warnings a second; one every 30s is enough.
     * It says how long the oldest waiting event has been stuck - the number to alert on.
     */
    private void warnThrottled(OutboxEvent oldest, int waiting, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAtMs >= 30_000) {
            lastWarnAtMs = now;
            long waitingSeconds = Duration.between(oldest.getCreatedAt(), LocalDateTime.now()).toSeconds();
            log.warn("Outbox: {} events waiting, the oldest (id {}, {}) for {}s - not delivered, retrying every poll: {}",
                    waiting, oldest.getId(), oldest.getTopic(), waitingSeconds, e.getMessage());
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
