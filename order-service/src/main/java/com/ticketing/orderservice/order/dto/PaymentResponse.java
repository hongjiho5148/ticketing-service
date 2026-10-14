package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.payment.PaymentStatus;
import java.time.LocalDateTime;

/** failureReason is set only for a FAILED payment the server itself unwound (e.g. "HOLD_EXPIRED"); null otherwise. */
public record PaymentResponse(Long orderId, PaymentStatus paymentStatus, LocalDateTime paidAt, String failureReason) {
}
