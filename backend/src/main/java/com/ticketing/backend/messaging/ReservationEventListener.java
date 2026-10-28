package com.ticketing.backend.messaging;

import com.ticketing.backend.queue.AdmissionUseTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the reservation-created event for what it says about the queue: how long after being let in a person holds
 * their first seat (AdmissionUseTracker). Only measures; nothing about who is let in or who loses a slot depends on it,
 * so a failure here is logged and not retried.
 */
@Component
public class ReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventListener.class);

    private final ObjectMapper objectMapper;
    private final AdmissionUseTracker admissionUseTracker;

    public ReservationEventListener(ObjectMapper objectMapper, AdmissionUseTracker admissionUseTracker) {
        this.objectMapper = objectMapper;
        this.admissionUseTracker = admissionUseTracker;
    }

    @KafkaListener(topics = KafkaTopics.RESERVATION_CREATED, groupId = "backend")
    public void onReservationCreated(String payload) {
        ReservationCreatedEvent event = objectMapper.readValue(payload, ReservationCreatedEvent.class);
        log.info("Received {}: {}", KafkaTopics.RESERVATION_CREATED, event);
        try {
            admissionUseTracker.onReservationCreated(event.userId(), event.eventId(), event.createdAt());
        } catch (RuntimeException e) {
            log.warn("could not record how soon user {} reserved after being let in", event.userId(), e);
        }
    }
}
