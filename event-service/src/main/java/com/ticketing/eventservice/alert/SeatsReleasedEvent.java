package com.ticketing.eventservice.alert;

/** 공연의 좌석이 다시 판매로 돌아갈 때 발행한다. 취소표 알림이 이 이벤트를 듣는다. */
public record SeatsReleasedEvent(Long eventId, int seatCount) {
}
