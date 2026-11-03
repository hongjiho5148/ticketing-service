package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

/** 결제 대상인 홀드를 지정한다. 그 홀드가 이미 없어졌다면 event-service가 좌석 판매를 거절한다. */
public record SellSeatRequest(LocalDateTime holdExpireAt) {
}
