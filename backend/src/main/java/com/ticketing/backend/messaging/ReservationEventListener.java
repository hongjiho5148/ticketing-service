package com.ticketing.backend.messaging;

import com.ticketing.backend.queue.AdmissionUseTracker;
import com.ticketing.backend.queue.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the reservation-created event for what it means for the queue. A person who has taken a seat is buying, so their
 * pass is lengthened (QueueService.extendPassesOfHolder) - that has to work, so an error there is retried and then
 * dead-lettered like any other. How soon after being let in they took the seat is only recorded for the metrics
 * (AdmissionUseTracker); a failure of that part is logged and not retried.
 */
@Component
public class ReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventListener.class);

    private final ObjectMapper objectMapper;
    private final AdmissionUseTracker admissionUseTracker;
    private final QueueService queueService;

    public ReservationEventListener(ObjectMapper objectMapper, AdmissionUseTracker admissionUseTracker, QueueService queueService) {
        this.objectMapper = objectMapper;
        this.queueService = queueService;
        this.admissionUseTracker = admissionUseTracker;
    }

    @KafkaListener(topics = KafkaTopics.RESERVATION_CREATED, groupId = "backend")
    public void onReservationCreated(String payload) {
        ReservationCreatedEvent event = objectMapper.readValue(payload, ReservationCreatedEvent.class);
        log.info("Received {}: {}", KafkaTopics.RESERVATION_CREATED, event);
        queueService.extendPassesOfHolder(event.userId(), event.eventId());
        try {
            admissionUseTracker.onReservationCreated(event.userId(), event.eventId(), event.createdAt());
        } catch (RuntimeException e) {
            log.warn("could not record how soon user {} reserved after being let in", event.userId(), e);
        }
    }
}
