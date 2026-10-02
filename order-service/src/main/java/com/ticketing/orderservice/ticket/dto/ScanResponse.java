package com.ticketing.orderservice.ticket.dto;

import java.time.LocalDateTime;

public record ScanResponse(Long ticketId, Long orderId, Long seatId, LocalDateTime usedAt) {
}
