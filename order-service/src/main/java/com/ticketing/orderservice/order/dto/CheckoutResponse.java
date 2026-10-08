package com.ticketing.orderservice.order.dto;

import java.time.LocalDateTime;

/** Everything the checkout screen needs to pick a pending order's payment back up where the user left it. */
public record CheckoutResponse(OrderResponse order, Long seatId, Long eventId, LocalDateTime holdExpireAt) {
}
