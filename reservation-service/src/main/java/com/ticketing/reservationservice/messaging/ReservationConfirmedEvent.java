package com.ticketing.reservationservice.messaging;

/**
 * 예약이 확정(결제)되면 한 번 발행한다. 대기열이 이것으로 구매자의 슬롯을 돌려준다. ReservationCreatedEvent처럼
 * 시각은 일반 ISO-8601 문자열이라 레코드를 (역)직렬화하기 간단하다.
 */
public record ReservationConfirmedEvent(
        Long reservationId, Long seatId, Long userId, Long eventId, String confirmedAt) {}
