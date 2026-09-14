package com.ticketing.eventservice.internal.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SeatBatchRequest(@NotEmpty List<Long> seatIds) {
}
