package com.ticketing.backend.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * How many people are in the queue right now, across all events: waiting for a slot, and holding one. Read from Redis
 * when Prometheus scrapes (every few seconds), not on the request path. The number of events with a queue is small, so
 * walking their keys at that rate costs nothing. (Per-event numbers are left out on purpose: a label per event would
 * grow the set of time series with every show that is ever created.)
 */
@Component
public class QueueMetrics implements MeterBinder {

    private final RedissonClient redissonClient;

    public QueueMetrics(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("queue_waiting_users", this, m -> m.total("queue:waiting:*"))
                .description("People waiting for an active slot, all events together")
                .register(registry);
        Gauge.builder("queue_active_users", this, m -> m.total("queue:active:*"))
                .description("People currently holding an active slot (a valid pass), all events together")
                .register(registry);
    }

    private double total(String pattern) {
        try {
            long total = 0;
            for (String key : redissonClient.getKeys().getKeysByPattern(pattern)) {
                RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(key);
                total += set.size();
            }
            return total;
        } catch (RuntimeException e) {
            return Double.NaN; // Redis is not answering: "unknown", not zero
        }
    }
}
