package com.ticketing.reservationservice.reservation;

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
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Not unique: event-service's hold() already serializes access to a seat with its own
    // per-seat Redis lock and AVAILABLE-status check before this row is ever written, so it's the
    // real concurrency guard. A DB-level unique constraint here would additionally mean "this seat
    // can only ever be booked once in its lifetime" - the same seat_id would collide against a
    // prior row forever, even a long-CANCELLED/EXPIRED one, permanently blocking every future
    // reservation for that seat once someone's very first hold on it is cancelled or expires.
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime holdExpireAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Reservation(Long userId, Long seatId, LocalDateTime holdExpireAt) {
        this.userId = userId;
        this.seatId = seatId;
        this.status = ReservationStatus.HOLDING;
        this.holdExpireAt = holdExpireAt;
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }
}
