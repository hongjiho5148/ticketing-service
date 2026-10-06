package com.ticketing.orderservice.order.dto;

import jakarta.validation.constraints.NotNull;

public record ApplyPointsRequest(@NotNull Integer points) {
}
