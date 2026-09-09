package com.ticketing.backend.order.dto;

import jakarta.validation.constraints.NotNull;

public record OrderCreateRequest(@NotNull Long reservationId) {
}
