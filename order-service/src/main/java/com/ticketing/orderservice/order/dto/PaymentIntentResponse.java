package com.ticketing.orderservice.order.dto;

/** The payment id to open the PortOne window with, and the amount to charge - both decided by the server. */
public record PaymentIntentResponse(String paymentId, Integer amount) {
}
