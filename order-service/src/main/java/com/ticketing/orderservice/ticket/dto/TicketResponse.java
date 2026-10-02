package com.ticketing.orderservice.ticket.dto;

import com.ticketing.orderservice.ticket.TicketStatus;
import java.time.LocalDateTime;

public record TicketResponse(Long ticketId, String qrToken, TicketStatus status, LocalDateTime issuedAt) {
}
