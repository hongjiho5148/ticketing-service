package com.ticketing.reservationservice.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 아웃박스가 얼마나 밀렸는지 보여 준다. 두 숫자 모두 Prometheus가 수집할 때(몇 초마다) 테이블에서 읽으므로
 * 재시작 뒤에도, 다른 인스턴스가 쓴 행에 대해서도 정확하다. 알림을 걸어야 할 것은 가장 오래 기다린 이벤트의
 * 나이다. 몇 초는 정상이고, 몇 분이면 Kafka(또는 릴레이)가 전달하지 못하고 있다는 뜻이다.
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
            return Double.NaN; // 데이터베이스가 응답하지 않는다: 0이 아니라 "알 수 없음"을 보고한다
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
