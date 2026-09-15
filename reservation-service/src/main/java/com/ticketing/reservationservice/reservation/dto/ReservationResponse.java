package com.ticketing.reservationservice.reservation.dto;

import com.ticketing.reservationservice.reservation.Reservation;
import com.ticketing.reservationservice.reservation.ReservationStatus;
import java.time.LocalDateTime;

public record ReservationResponse(Long reservationId, Long seatId, ReservationStatus status, LocalDateTime holdExpireAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(), reservation.getSeatId(), reservation.getStatus(), reservation.getHoldExpireAt());
    }
}
