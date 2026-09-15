package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

/** Mirrors event-service's internal SeatDetailResponse - an embedded event summary so most calls need only one round trip. */
public record SeatDetailResponse(
        Long seatId,
        Long eventId,
        String eventTitle,
        String venue,
        LocalDateTime eventStartAt,
        String grade,
        String section,
        Integer rowNo,
        Integer seatNumber,
        String seatNo,
        Integer price,
        String status) {
}
