package com.ticketing.eventservice.internal.dto;

import java.time.LocalDateTime;

/**
 * Which hold the caller means to release: the holdExpireAt it was given when it held the seat. A release that names
 * a hold only takes effect while the seat is still held by exactly that hold (see Seat.isHeldWith).
 */
public record ReleaseSeatRequest(LocalDateTime holdExpireAt) {
}
