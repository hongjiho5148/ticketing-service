package com.ticketing.orderservice.order;

public enum OrderStatus {
    PENDING, PAID, FAILED, CANCELLED,
    /** Cancelled, but late enough that only part of the payment was refunded (see RefundPolicy). */
    PARTIALLY_REFUNDED
}
