package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

/** Names the hold being paid for, so event-service refuses to sell the seat if that hold is already gone. */
public record SellSeatRequest(LocalDateTime holdExpireAt) {
}
