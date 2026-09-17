package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.payment.PaymentStatus;
import java.time.LocalDateTime;

public record PaymentResponse(Long orderId, PaymentStatus paymentStatus, LocalDateTime paidAt) {
}
