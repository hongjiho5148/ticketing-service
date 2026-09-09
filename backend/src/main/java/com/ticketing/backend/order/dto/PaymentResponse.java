package com.ticketing.backend.order.dto;

import com.ticketing.backend.payment.PaymentStatus;
import java.time.LocalDateTime;

public record PaymentResponse(Long orderId, PaymentStatus paymentStatus, LocalDateTime paidAt) {
}
