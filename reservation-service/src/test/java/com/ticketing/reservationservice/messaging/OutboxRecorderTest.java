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
 * An event may only be recorded inside the transaction of the change it describes. Deliberately NOT @Transactional, so
 * there is no transaction here. Compose MySQL; run inside the compose network with DB_HOST=mysql.
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
