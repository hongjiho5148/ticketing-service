package com.ticketing.orderservice.order;

public enum OrderStatus {
    PENDING, PAID, FAILED, CANCELLED,
    /** 취소됐지만 너무 늦어서 결제 금액의 일부만 환불됐다(RefundPolicy 참고). */
    PARTIALLY_REFUNDED
}
