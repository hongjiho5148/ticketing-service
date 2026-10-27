package com.ticketing.reservationservice.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records an event to be published. Writing the row is only worth anything when it commits or rolls back together with
 * the change it describes - that is the whole point of an outbox - so a transaction must already be open
 * (MANDATORY): calling this on its own would silently recreate the "saved but maybe never published" problem.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String topic, String key, Object event) {
        repository.save(new OutboxEvent(topic, key, objectMapper.writeValueAsString(event)));
    }
}
