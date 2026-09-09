package com.ticketing.backend.order.dto;

import com.ticketing.backend.order.OrderStatus;
import com.ticketing.backend.order.Orders;
import java.time.LocalDateTime;

public record OrderHistoryResponse(
        Long orderId, String eventTitle, String seatNo, Integer totalPrice, OrderStatus status, LocalDateTime createdAt) {

    public static OrderHistoryResponse from(Orders order) {
        var seat = order.getReservation().getSeat();
        return new OrderHistoryResponse(
                order.getId(),
                seat.getEvent().getTitle(),
                seat.getSeatNo(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
