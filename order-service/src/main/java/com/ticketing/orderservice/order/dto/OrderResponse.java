package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;

public record OrderResponse(
        Long orderId,
        Long reservationId,
        Integer originalPrice,
        String couponCode,
        Integer discountAmount,
        Integer pointsUsed,
        Integer totalPrice,
        OrderStatus status) {

    public static OrderResponse from(Orders order) {
        return new OrderResponse(
                order.getId(),
                order.getReservationId(),
                order.originalPrice(),
                order.getCouponCode(),
                order.getDiscountAmount(),
                order.getPointsUsed(),
                order.getTotalPrice(),
                order.getStatus());
    }
}
