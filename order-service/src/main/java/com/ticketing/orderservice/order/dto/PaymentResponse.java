package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.payment.PaymentStatus;
import java.time.LocalDateTime;

/** failureReason은 서버가 직접 되돌린 FAILED 결제에만 설정된다(예: "HOLD_EXPIRED"). 그 밖에는 null이다. */
public record PaymentResponse(Long orderId, PaymentStatus paymentStatus, LocalDateTime paidAt, String failureReason) {
}
