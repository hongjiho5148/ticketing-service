package com.ticketing.reservationservice.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Fire-and-forget: a Kafka hiccup must never fail the reservation flow it's reporting on. */
@Component
public class ReservationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ReservationEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishReservationCreated(ReservationCreatedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate
                    .send(KafkaTopics.RESERVATION_CREATED, event.reservationId().toString(), payload)
                    .exceptionally(ex -> {
                        log.warn("Failed to publish {}: {}", KafkaTopics.RESERVATION_CREATED, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Failed to publish {}: {}", KafkaTopics.RESERVATION_CREATED, e.getMessage());
        }
    }
}
