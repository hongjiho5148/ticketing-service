package com.ticketing.backend.queue;

import com.ticketing.backend.event.Event;
import com.ticketing.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

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

    public WaitingQueue(User user, Event event, String queueToken, Long rankNo) {
        this.user = user;
        this.event = event;
        this.queueToken = queueToken;
        this.rankNo = rankNo;
        this.status = QueueEntryStatus.WAITING;
    }

    public void pass() {
        this.status = QueueEntryStatus.PASSED;
        this.passedAt = LocalDateTime.now();
    }
}
