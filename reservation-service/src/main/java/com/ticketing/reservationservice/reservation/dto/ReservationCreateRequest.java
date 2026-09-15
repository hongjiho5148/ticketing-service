package com.ticketing.reservationservice.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record ReservationCreateRequest(@NotNull Long seatId) {
}
