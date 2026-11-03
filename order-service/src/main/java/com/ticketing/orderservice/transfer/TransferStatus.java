package com.ticketing.orderservice.transfer;

public enum TransferStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    /** 받는 사람이 응답하기 전에 보낸 사람이 거둬들였다. */
    CANCELLED,
    /** 받는 사람이 응답하기 전에 창이 닫혔다(또는 주문이 더는 양도할 수 없게 됐다). */
    EXPIRED
}
