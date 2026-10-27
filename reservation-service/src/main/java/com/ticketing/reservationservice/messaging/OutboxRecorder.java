package com.ticketing.reservationservice.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Records an event to be published. Writing the row is only worth anything when it commits or rolls back together with
 * the change it describes - that is the whole point of an outbox - so a transaction must already be open
 * (MANDATORY): calling this on its own would silently recreate the "saved but maybe never published" problem.
 *
 * <p>Once that transaction has committed, the relay is nudged so the event leaves right away rather than at the next
 * poll. Nothing depends on the nudge: if it is missed the scheduled poll sends the row anyway.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final OutboxRelay relay;
    private final boolean kickAfterCommit;

    public OutboxRecorder(
            OutboxEventRepository repository,
            ObjectMapper objectMapper,
            OutboxRelay relay,
            @Value("${outbox.kick-after-commit:true}") boolean kickAfterCommit) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.relay = relay;
        this.kickAfterCommit = kickAfterCommit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String topic, String key, Object event) {
        repository.save(new OutboxEvent(topic, key, objectMapper.writeValueAsString(event)));
        if (kickAfterCommit) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.kick();
                }
            });
        }
    }
}
