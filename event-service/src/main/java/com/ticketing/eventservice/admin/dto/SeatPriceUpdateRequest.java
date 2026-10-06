package com.ticketing.eventservice.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SeatPriceUpdateRequest(@NotNull @Min(0) Integer price) {
}
