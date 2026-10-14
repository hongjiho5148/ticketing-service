package com.ticketing.reservationservice.messaging;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Fire-and-forget: a Kafka hiccup must never fail - or even slow down - the reservation flow it's reporting on.
 * KafkaTemplate.send() is asynchronous for the delivery but can still block the calling thread (metadata refresh, a full
 * producer buffer): under load that showed up as 1-3 second stalls inside reserve(). So the send is handed to a small
 * bounded pool; if the pool is backed up the event is dropped, which is acceptable for an informational event.
 */
@Component
public class ReservationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService sender = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.DiscardPolicy());

    public ReservationEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishReservationCreated(ReservationCreatedEvent event) {
        sender.execute(() -> send(event));
    }

    private void send(ReservationCreatedEvent event) {
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
