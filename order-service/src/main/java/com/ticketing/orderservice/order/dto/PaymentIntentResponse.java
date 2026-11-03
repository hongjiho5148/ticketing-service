package com.ticketing.orderservice.order.dto;

/** PortOne 창을 여는 데 쓸 결제 id와 청구할 금액이다. 둘 다 서버가 정한다. */
public record PaymentIntentResponse(String paymentId, Integer amount) {
}
