package com.ticketing.orderservice.order;

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

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    // Denormalized from the reservation at order-creation time - it never changes afterward, so
    // caching it here avoids a reservation-service round trip on every order-history render.
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    // Denormalized from event-service at order-creation time too, for the same reason - lets the
    // QR-ticket issuance sweeper find "paid orders whose show starts soon" with a local query
    // instead of calling event-service once per candidate order on every tick.
    @Column(name = "event_start_at", nullable = false)
    private LocalDateTime eventStartAt;

    // Also denormalized from event-service at creation time - lets event-service check "does this
    // user have a paid order for this event" (review-write eligibility) via one local query here
    // instead of resolving every order's seatId back to an eventId itself.
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    // Set once the D-1 reminder mail goes out, so ReminderSweeper's every-minute tick doesn't
    // re-send it on every pass through the "within 24-25h of the show" window.
    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    @Column(nullable = false)
    private Integer totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Orders(
            Long userId, Long reservationId, Long seatId, LocalDateTime eventStartAt, Long eventId, Integer totalPrice) {
        this.userId = userId;
        this.reservationId = reservationId;
        this.seatId = seatId;
        this.eventStartAt = eventStartAt;
        this.eventId = eventId;
        this.totalPrice = totalPrice;
        this.status = OrderStatus.PENDING;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public void markReminderSent() {
        this.reminderSentAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = OrderStatus.FAILED;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }
}
