package com.ticketing.orderservice.order.dto;

import java.time.LocalDateTime;

/** 결제 화면이 결제 대기 주문의 결제를 사용자가 떠난 지점에서 다시 이어 가는 데 필요한 모든 것이다. */
public record CheckoutResponse(OrderResponse order, Long seatId, Long eventId, LocalDateTime holdExpireAt) {
}
