package com.ticketing.orderservice.ticket.dto;

import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.ticket.Ticket;
import com.ticketing.orderservice.ticket.TicketStatus;
import java.time.LocalDateTime;

public record TicketHistoryResponse(
        Long ticketId,
        Long orderId,
        Long eventId,
        String eventTitle,
        String venue,
        String grade,
        String section,
        Integer rowNo,
        Integer seatNumber,
        String seatNo,
        LocalDateTime eventStartAt,
        TicketStatus status,
        LocalDateTime issuedAt) {

    public static TicketHistoryResponse from(Ticket ticket, SeatDetailResponse seat) {
        return new TicketHistoryResponse(
                ticket.getId(),
                ticket.getOrder().getId(),
                seat.eventId(),
                seat.eventTitle(),
                seat.venue(),
                seat.grade(),
                seat.section(),
                seat.rowNo(),
                seat.seatNumber(),
                seat.seatNo(),
                ticket.getOrder().getEventStartAt(),
                ticket.getStatus(),
                ticket.getIssuedAt());
    }
}
