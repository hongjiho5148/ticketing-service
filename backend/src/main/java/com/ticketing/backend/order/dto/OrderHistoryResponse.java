package com.ticketing.backend.order.dto;

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

    public static OrderHistoryResponse from(Orders order) {
        var seat = order.getReservation().getSeat();
        var event = seat.getEvent();
        return new OrderHistoryResponse(
                order.getId(),
                event.getId(),
                event.getTitle(),
                event.getVenue(),
                seat.getSeatGrade(),
                seat.getSection(),
                seat.getRowNo(),
                seat.getSeatNumber(),
                seat.getSeatNo(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
