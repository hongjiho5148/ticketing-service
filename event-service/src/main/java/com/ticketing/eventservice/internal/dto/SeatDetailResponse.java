package com.ticketing.eventservice.internal.dto;

import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatStatus;
import java.time.LocalDateTime;

/** Service-to-service seat detail - includes an embedded event summary so callers (backend) rarely need a second round trip. */
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
        SeatStatus status) {

    public static SeatDetailResponse from(Seat seat) {
        return new SeatDetailResponse(
                seat.getId(),
                seat.getEvent().getId(),
                seat.getEvent().getTitle(),
                seat.getEvent().getVenue(),
                seat.getEvent().getStartAt(),
                seat.getSeatGrade(),
                seat.getSection(),
                seat.getRowNo(),
                seat.getSeatNumber(),
                seat.getSeatNo(),
                seat.getPrice(),
                seat.getStatus());
    }
}
