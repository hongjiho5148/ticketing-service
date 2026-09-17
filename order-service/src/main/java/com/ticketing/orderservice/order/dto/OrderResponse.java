package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;

public record OrderResponse(Long orderId, Long reservationId, Integer totalPrice, OrderStatus status) {

    public static OrderResponse from(Orders order) {
        return new OrderResponse(order.getId(), order.getReservationId(), order.getTotalPrice(), order.getStatus());
    }
}
