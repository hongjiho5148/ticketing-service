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

    /** Null while the user is still waiting; set once the mail went out (or they turned out to be opted out). */
    private LocalDateTime notifiedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WaitlistSubscription(Long userId, Long eventId) {
        this.userId = userId;
        this.eventId = eventId;
    }

    /** Subscribing again after being notified re-arms the alert. */
    public void rearm() {
        this.notifiedAt = null;
    }
}
