package com.ticketing.backend.eventclient.dto;

import java.time.LocalDateTime;

public record HoldSeatRequest(LocalDateTime holdExpireAt) {
}
