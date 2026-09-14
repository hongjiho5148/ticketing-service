package com.ticketing.backend.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * History/stats record of a queue entry. The live queue position is tracked in Redis (see
 * QueueService/QueueAdmissionSweeper); this table just records what happened, matching the
 * original design's "실제 순번은 Redis ZSET에서 관리, 이 테이블은 통계/이력 기록용" note.
 */
@Entity
@Table(name = "waiting_queue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitingQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false, unique = true, length = 40)
    private String queueToken;

    @Column(nullable = false)
    private Long rankNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueEntryStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime enteredAt;

    private LocalDateTime passedAt;

    public WaitingQueue(Long userId, Long eventId, String queueToken, Long rankNo) {
        this.userId = userId;
        this.eventId = eventId;
        this.queueToken = queueToken;
        this.rankNo = rankNo;
        this.status = QueueEntryStatus.WAITING;
    }

    public void pass() {
        this.status = QueueEntryStatus.PASSED;
        this.passedAt = LocalDateTime.now();
    }
}
