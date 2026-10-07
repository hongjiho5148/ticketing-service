package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.payment.Payment;
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
        String paymentMethod,
        Integer refundedAmount,
        OrderStatus status,
        // "PENDING" while a transfer awaits the recipient, "TRANSFERRED" once accepted, else null.
        String transferStatus,
        boolean transferable,
        LocalDateTime createdAt) {

    /** payment is null for orders that never reached a payment attempt (still PENDING). */
    public static OrderHistoryResponse from(
            Orders order, SeatDetailResponse seat, Payment payment, String transferStatus, boolean transferable) {
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
                payment == null ? null : payment.getMethod(),
                payment == null ? null : payment.getRefundedAmount(),
                order.getStatus(),
                transferStatus,
                transferable,
                order.getCreatedAt());
    }
}
