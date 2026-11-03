package com.ticketing.reservationservice.messaging;

public final class KafkaTopics {

    public static final String RESERVATION_CREATED = "reservation.created";
    /** 예약이 결제됐다. 구매자는 대기열에서 할 일이 끝났다. */
    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";

    private KafkaTopics() {}
}
