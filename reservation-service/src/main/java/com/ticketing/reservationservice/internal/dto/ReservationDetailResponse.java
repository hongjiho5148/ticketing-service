package com.ticketing.reservationservice.internal.dto;

import com.ticketing.reservationservice.reservation.Reservation;
import com.ticketing.reservationservice.reservation.ReservationStatus;
import java.time.LocalDateTime;

public record ReservationDetailResponse(
        Long reservationId, Long userId, Long seatId, ReservationStatus status, LocalDateTime holdExpireAt) {

    public static ReservationDetailResponse from(Reservation reservation) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getSeatId(),
                reservation.getStatus(),
                reservation.getHoldExpireAt());
    }
}
