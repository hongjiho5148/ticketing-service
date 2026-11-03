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
        // 양도가 받는 사람의 응답을 기다리면 "PENDING", 수락되면 "TRANSFERRED", 아니면 null이다.
        String transferStatus,
        boolean transferable,
        // 좌석이 아직 잡혀 있는 PENDING 주문에만 설정한다. 그때까지는 결제를 이어서 할 수 있다.
        LocalDateTime holdExpireAt,
        LocalDateTime createdAt) {

    /** 결제를 한 번도 시도하지 않은 주문(아직 PENDING)은 payment가 null이다. */
    public static OrderHistoryResponse from(
            Orders order,
            SeatDetailResponse seat,
            Payment payment,
            String transferStatus,
            boolean transferable,
            LocalDateTime holdExpireAt) {
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
                holdExpireAt,
                order.getCreatedAt());
    }
}
