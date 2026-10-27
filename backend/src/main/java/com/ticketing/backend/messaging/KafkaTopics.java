package com.ticketing.backend.messaging;

public final class KafkaTopics {

    public static final String RESERVATION_CREATED = "reservation.created";
    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";

    private KafkaTopics() {}
}
