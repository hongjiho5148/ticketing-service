package com.ticketing.backend.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 지금 모든 공연에 걸쳐 대기열에 몇 명이 있는지다. 슬롯을 기다리는 사람과 슬롯을 가진 사람이다. Prometheus가 수집할 때
 * (몇 초마다) Redis에서 읽으며 요청 경로에서는 읽지 않는다. 대기열이 있는 공연의 수는 적어서 그 주기로 키를
 * 훑어도 비용이 들지 않는다. (공연별 숫자는 일부러 뺐다. 공연마다 레이블을 달면 공연이 만들어질 때마다
 * 시계열의 수가 늘어난다.)
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
            return Double.NaN; // Redis가 응답하지 않는다: 0이 아니라 "알 수 없음"
        }
    }
}
