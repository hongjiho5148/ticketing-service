package com.ticketing.eventservice.internal.dto;

import java.time.LocalDateTime;

/** The hold the buyer is paying for: the seat is only sold while it is still held by exactly that hold. */
public record SellSeatRequest(LocalDateTime holdExpireAt) {
}
