package com.ticketing.backend.messaging;

/** 예약이 결제되면 reservation-service가 발행한다. 그쪽의 ReservationConfirmedEvent 참고. */
public record ReservationConfirmedEvent(
        Long reservationId, Long seatId, Long userId, Long eventId, String confirmedAt) {}
