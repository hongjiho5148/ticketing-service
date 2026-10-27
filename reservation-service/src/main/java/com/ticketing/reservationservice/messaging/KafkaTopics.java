package com.ticketing.reservationservice.messaging;

public final class KafkaTopics {

    public static final String RESERVATION_CREATED = "reservation.created";
    /** A reservation was paid for: the buyer is done with the queue. */
    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";

    private KafkaTopics() {}
}
