package com.ticketing.backend.order.dto;

import com.ticketing.backend.eventclient.dto.SeatDetailResponse;
import com.ticketing.backend.order.OrderStatus;
import com.ticketing.backend.order.Orders;
import java.time.LocalDateTime;

public record OrderHistoryResponse(
        Long orderId,
        Long eventId,
        String eventTitle,
        String venue,
        String grade,
        String section,
        Integer rowNo,
        Integer seatNumber,
        String seatNo,
        Integer totalPrice,
        OrderStatus status,
        LocalDateTime createdAt) {

    public static OrderHistoryResponse from(Orders order, SeatDetailResponse seat) {
        return new OrderHistoryResponse(
                order.getId(),
                seat.eventId(),
                seat.eventTitle(),
                seat.venue(),
                seat.grade(),
                seat.section(),
                seat.rowNo(),
                seat.seatNumber(),
                seat.seatNo(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
