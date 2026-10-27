package com.ticketing.backend.messaging;

import com.ticketing.backend.queue.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A buyer who has paid is done with the queue, so their active slot goes back and the next waiter can be let in at the
 * admission sweeper's next tick instead of when the pass token would have run out.
 *
 * <p>Delivery is at-least-once, so this has to be safe to receive twice - and it is: handing back a slot that was already
 * handed back changes nothing. An unreadable message is retried and then dead-lettered by the container's error handler
 * (see KafkaConfig).
 */
@Component
public class ReservationConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationConfirmedListener.class);

    private final QueueService queueService;
    private final ObjectMapper objectMapper;

    public ReservationConfirmedListener(QueueService queueService, ObjectMapper objectMapper) {
        this.queueService = queueService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.RESERVATION_CONFIRMED, groupId = "backend")
    public void onReservationConfirmed(String payload) {
        ReservationConfirmedEvent event = objectMapper.readValue(payload, ReservationConfirmedEvent.class);
        int released = queueService.releaseSlotsOfBuyer(event.userId(), event.eventId());
        log.info("Reservation {} confirmed: handed back {} queue slot(s) of user {} for event {}",
                event.reservationId(), released, event.userId(), event.eventId());
    }
}
