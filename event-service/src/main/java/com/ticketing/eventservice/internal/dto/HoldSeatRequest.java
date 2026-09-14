package com.ticketing.eventservice.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record HoldSeatRequest(@NotNull LocalDateTime holdExpireAt) {
}
