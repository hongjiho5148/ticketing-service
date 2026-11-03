package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

/** 풀려는 홀드를 지정한다. 그 사이 좌석이 다른 사람에게 다시 잡혔다면 event-service가 이 요청을 무시한다. */
public record ReleaseSeatRequest(LocalDateTime holdExpireAt) {
}
