package com.ticketing.orderservice.admin.dto;

import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.payment.Payment;
import java.time.LocalDateTime;

/** One row of the admin order list. buyerName/buyerEmail are null when auth-service can't resolve the user. */
public record AdminOrderResponse(
        Long orderId,
        Long eventId,
        String eventTitle,
        String grade,
        String section,
        String seatNo,
        Integer totalPrice,
        Integer refundedAmount,
        OrderStatus status,
        String paymentMethod,
        Long buyerId,
        String buyerName,
        String buyerEmail,
        LocalDateTime createdAt) {

    public static AdminOrderResponse from(
            Orders order, SeatDetailResponse seat, Payment payment, String buyerName, String buyerEmail) {
        return new AdminOrderResponse(
                order.getId(),
                order.getEventId(),
                seat == null ? null : seat.eventTitle(),
                seat == null ? null : seat.grade(),
                seat == null ? null : seat.section(),
                seat == null ? null : seat.seatNo(),
                order.getTotalPrice(),
                payment == null ? null : payment.getRefundedAmount(),
                order.getStatus(),
                payment == null ? null : payment.getMethod(),
                order.getUserId(),
                buyerName,
                buyerEmail,
                order.getCreatedAt());
    }
}
