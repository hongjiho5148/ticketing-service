package com.ticketing.reservationservice.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 발행할 이벤트를 기록한다. 행을 쓰는 것은 그 이벤트가 설명하는 변경과 함께 커밋되거나 롤백될 때만 의미가 있다.
 * 그것이 아웃박스의 핵심이다. 그래서 트랜잭션이 이미 열려 있어야 하고(MANDATORY), 이것을 단독으로 호출하면
 * "저장은 됐지만 발행은 안 될 수 있다"는 문제를 조용히 되살리게 된다.
 *
 * <p>그 트랜잭션이 커밋되면 릴레이를 깨워서 이벤트가 다음 폴링을 기다리지 않고 바로 나가게 한다. 이 깨우기에
 * 의존하는 것은 없다. 놓쳐도 예약된 폴링이 어차피 그 행을 보낸다.
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
