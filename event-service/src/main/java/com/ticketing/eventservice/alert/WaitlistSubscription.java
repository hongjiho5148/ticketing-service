package com.ticketing.eventservice.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
        name = "waitlist_subscription",
        uniqueConstraints = @UniqueConstraint(name = "uk_waitlist_user_event", columnNames = {"user_id", "event_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitlistSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 사용자가 아직 기다리는 동안은 null이다. 메일이 나갔거나(또는 수신 거부로 확인됐을 때) 설정한다. */
    private LocalDateTime notifiedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WaitlistSubscription(Long userId, Long eventId) {
        this.userId = userId;
        this.eventId = eventId;
    }

    /** 알림을 받은 뒤 다시 구독하면 알림이 다시 걸린다. */
    public void rearm() {
        this.notifiedAt = null;
    }
}
