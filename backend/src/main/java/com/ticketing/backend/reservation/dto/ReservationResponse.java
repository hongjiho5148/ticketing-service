package com.ticketing.backend.reservation.dto;

import com.ticketing.backend.reservation.Reservation;
import com.ticketing.backend.reservation.ReservationStatus;
import java.time.LocalDateTime;

public record ReservationResponse(Long reservationId, Long seatId, ReservationStatus status, LocalDateTime holdExpireAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(), reservation.getSeat().getId(), reservation.getStatus(), reservation.getHoldExpireAt());
    }
}
