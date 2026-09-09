package com.ticketing.backend.order.dto;

import com.ticketing.backend.order.OrderStatus;
import com.ticketing.backend.order.Orders;

public record OrderResponse(Long orderId, Long reservationId, Integer totalPrice, OrderStatus status) {

    public static OrderResponse from(Orders order) {
        return new OrderResponse(order.getId(), order.getReservation().getId(), order.getTotalPrice(), order.getStatus());
    }
}
