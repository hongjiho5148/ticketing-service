package com.ticketing.backend.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Placeholder consumer proving the Kafka round-trip end to end. Once the Order/Queue services are
 * split out (MSA 3단계), they'll each run their own listener on this topic instead of this one.
 */
@Component
public class ReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventListener.class);

    private final ObjectMapper objectMapper;

    public ReservationEventListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.RESERVATION_CREATED, groupId = "backend")
    public void onReservationCreated(String payload) {
        ReservationCreatedEvent event = objectMapper.readValue(payload, ReservationCreatedEvent.class);
        log.info("Received {}: {}", KafkaTopics.RESERVATION_CREATED, event);
    }
}
