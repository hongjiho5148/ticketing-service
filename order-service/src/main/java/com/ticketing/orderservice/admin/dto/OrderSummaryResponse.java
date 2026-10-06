package com.ticketing.orderservice.admin.dto;

public record OrderSummaryResponse(Long eventId, long paidCount, long cancelledCount, long revenue) {
}
