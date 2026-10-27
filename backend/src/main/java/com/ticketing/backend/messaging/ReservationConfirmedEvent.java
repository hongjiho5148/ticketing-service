package com.ticketing.backend.messaging;

/** Published by reservation-service once a reservation has been paid for; see its ReservationConfirmedEvent. */
public record ReservationConfirmedEvent(
        Long reservationId, Long seatId, Long userId, Long eventId, String confirmedAt) {}
