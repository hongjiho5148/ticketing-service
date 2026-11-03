package com.ticketing.reservationservice.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** Kafka에 닿아야 하는 이벤트다. 기록은 {@link OutboxRecorder}, 발행은 {@link OutboxRelay}를 참고. */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    /** Kafka 메시지 키. 키가 같은 이벤트는 한 파티션 안에서 순서가 유지된다. */
    @Column(nullable = false, length = 100)
    private String eventKey;

    @Column(nullable = false, length = 2000)
    private String payload;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 브로커가 이벤트를 확인하기 전까지는 null이다. */
    private LocalDateTime sentAt;

    /** 이 행을 전송하려고 쥐고 있는 릴레이 폴링과 그 임대 만료 시각이다(OutboxEventRepository.claim 참고). */
    @Column(length = 64)
    private String claimedBy;

    private LocalDateTime claimedUntil;

    public OutboxEvent(String topic, String eventKey, String payload) {
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
    }
}
