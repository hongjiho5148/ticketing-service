package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 이벤트는 그것이 설명하는 변경의 트랜잭션 안에서만 기록할 수 있다. 일부러 @Transactional로 하지 않아서
 * 여기에는 트랜잭션이 없다. compose MySQL을 쓰고, compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-"
})
class OutboxRecorderTest {

    @Autowired private OutboxRecorder recorder;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void refusesToRecordAnEventOutsideATransaction() {
        assertThatThrownBy(() -> recorder.record("topic", "key", Map.of("a", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
