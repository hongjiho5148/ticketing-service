package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

/** Names the hold being released, so event-service ignores the request if the seat has since been re-held by someone else. */
public record ReleaseSeatRequest(LocalDateTime holdExpireAt) {
}
