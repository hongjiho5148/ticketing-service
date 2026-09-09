package com.ticketing.backend.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record ReservationCreateRequest(@NotNull Long seatId) {
}
