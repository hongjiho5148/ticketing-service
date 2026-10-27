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

/** An event that has to reach Kafka. See {@link OutboxRecorder} (writes it) and {@link OutboxRelay} (publishes it). */
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

    /** Kafka message key: events with the same key stay in order on one partition. */
    @Column(nullable = false, length = 100)
    private String eventKey;

    @Column(nullable = false, length = 2000)
    private String payload;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Null until the broker acknowledged the event. */
    private LocalDateTime sentAt;

    /** The relay poll that holds this row for sending, and until when (see OutboxEventRepository.claim). */
    @Column(length = 64)
    private String claimedBy;

    private LocalDateTime claimedUntil;

    public OutboxEvent(String topic, String eventKey, String payload) {
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
    }
}
