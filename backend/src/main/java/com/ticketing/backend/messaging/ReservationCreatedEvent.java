package com.ticketing.backend.messaging;

/**
 * Published after a reservation is successfully created - the payload future consumers
 * (Order/Queue services) will need. createdAt is a plain ISO-8601 string (not LocalDateTime) so
 * this record serializes with Kafka's default JsonSerializer without needing a JSR-310 module.
 */
public record ReservationCreatedEvent(
        Long reservationId, Long seatId, Long userId, Long eventId, String createdAt) {}
