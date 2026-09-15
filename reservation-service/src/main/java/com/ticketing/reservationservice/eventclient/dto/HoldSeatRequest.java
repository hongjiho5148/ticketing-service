package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

public record HoldSeatRequest(LocalDateTime holdExpireAt) {
}
