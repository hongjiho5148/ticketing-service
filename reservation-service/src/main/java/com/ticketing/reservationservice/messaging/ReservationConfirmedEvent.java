package com.ticketing.reservationservice.messaging;

/**
 * Published once a reservation has been confirmed (paid for). The queue uses it to hand the buyer's slot back. As with
 * ReservationCreatedEvent the timestamp is a plain ISO-8601 string so the record is trivial to (de)serialize.
 */
public record ReservationConfirmedEvent(
        Long reservationId, Long seatId, Long userId, Long eventId, String confirmedAt) {}
