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
 * 대기열 항목의 이력/통계 기록이다. 실시간 대기 순번은 Redis에서 추적한다(QueueService/QueueAdmissionSweeper
 * 참고). 이 테이블은 일어난 일만 기록하며, 원래 설계의
 * "실제 순번은 Redis ZSET에서 관리, 이 테이블은 통계/이력 기록용" 메모를 따른다.
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
