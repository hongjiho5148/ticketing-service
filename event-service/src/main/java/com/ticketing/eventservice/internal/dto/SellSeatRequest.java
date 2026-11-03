package com.ticketing.eventservice.internal.dto;

import java.time.LocalDateTime;

/** 구매자가 결제하는 홀드다. 좌석은 정확히 그 홀드에 아직 잡혀 있는 동안에만 판매된다. */
public record SellSeatRequest(LocalDateTime holdExpireAt) {
}
