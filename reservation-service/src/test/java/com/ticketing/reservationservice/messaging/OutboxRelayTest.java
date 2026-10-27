package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.ReservationService;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import com.ticketing.reservationservice.reservation.dto.ReservationResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox relay against the compose MySQL, with Kafka mocked (rolled back, so the relay's "mark as sent" updates and
 * everything it reads stay inside the test's transaction). Each test records its events on a topic of its own and only
 * looks at that topic, so rows that happen to be in the table don't matter. Run inside the compose network with
 * DB_HOST=mysql.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-"
})
@Transactional
class OutboxRelayTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxRecorder recorder;
    @Autowired private OutboxEventRepository repository;
    @Autowired private ReservationService reservationService;
    @PersistenceContext private EntityManager em;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private QueueServiceClient queueServiceClient;

    private String topic;

    @BeforeEach
    void setUp() {
        topic = "outbox-test-" + SEQ.incrementAndGet();
        brokerAccepts();
    }

    private void brokerAccepts() {
        reset(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void record(String key) {
        recorder.record(topic, key, Map.of("key", key));
    }

    private List<OutboxEvent> mine() {
        em.flush();
        em.clear();
        return repository.findAll().stream().filter(e -> e.getTopic().equals(topic)).toList();
    }

    @Test
    void sendsRecordedEventsInTheOrderTheyWereRecordedAndMarksThemSent() {
        record("a");
        record("b");
        record("c");

        relay.relayOnce();

        InOrder order = inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send(eq(topic), eq("a"), anyString());
        order.verify(kafkaTemplate).send(eq(topic), eq("b"), anyString());
        order.verify(kafkaTemplate).send(eq(topic), eq("c"), anyString());
        assertThat(mine()).hasSize(3).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
    }

    @Test
    void anEventThatWasAcknowledgedIsNotSentAgain() {
        record("a");

        relay.relayOnce();
        relay.relayOnce();

        verify(kafkaTemplate, times(1)).send(eq(topic), eq("a"), anyString());
    }

    @Test
    void whenTheBrokerIsDownNothingIsLostAndEverythingGoesOutInOrderOnceItIsBack() {
        record("a");
        record("b");
        record("c");
        when(kafkaTemplate.send(eq(topic), eq("b"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.relayOnce();

        // a got through; b failed, so c must not overtake it
        verify(kafkaTemplate).send(eq(topic), eq("a"), anyString());
        verify(kafkaTemplate, never()).send(eq(topic), eq("c"), anyString());
        assertThat(mine()).extracting(OutboxEvent::getEventKey, e -> e.getSentAt() != null)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", true),
                        org.assertj.core.groups.Tuple.tuple("b", false),
                        org.assertj.core.groups.Tuple.tuple("c", false));

        // the broker is back: the next poll resumes at b
        brokerAccepts();
        relay.relayOnce();

        InOrder order = inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send(eq(topic), eq("b"), anyString());
        order.verify(kafkaTemplate).send(eq(topic), eq("c"), anyString());
        verify(kafkaTemplate, never()).send(eq(topic), eq("a"), anyString()); // reset above; a was not resent
        assertThat(mine()).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
    }

    @Test
    void reservingRecordsTheCreatedEventWithTheReservationAndTheRelayPublishesIt() {
        long seatId = SEQ.incrementAndGet();
        long eventId = 8_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(queueServiceClient.isPassTokenValid(anyLong(), any())).thenReturn(true);
        SeatDetailResponse seat = new SeatDetailResponse(seatId, eventId, "공연", "홀", LocalDateTime.now().plusDays(10),
                "R", "A", 1, 1, "1열 1번", 10000, "AVAILABLE");
        when(eventServiceClient.getSeat(anyLong())).thenReturn(seat);
        when(eventServiceClient.hold(anyLong(), any())).thenReturn(seat);

        ReservationResponse reservation = reservationService.reserve(userId, new ReservationCreateRequest(seatId), "pass");

        em.flush();
        em.clear();
        List<OutboxEvent> recorded = repository.findAll().stream()
                .filter(e -> e.getTopic().equals(KafkaTopics.RESERVATION_CREATED)
                        && e.getEventKey().equals(reservation.reservationId().toString()))
                .toList();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getSentAt()).isNull();
        assertThat(recorded.get(0).getPayload())
                .contains("\"reservationId\":" + reservation.reservationId())
                .contains("\"seatId\":" + seatId)
                .contains("\"userId\":" + userId)
                .contains("\"eventId\":" + eventId);

        relay.relayOnce();

        verify(kafkaTemplate).send(eq(KafkaTopics.RESERVATION_CREATED), eq(reservation.reservationId().toString()),
                eq(recorded.get(0).getPayload()));
    }

    @Test
    void purgeDeletesOnlyOldEventsThatWereAlreadySent() {
        record("old-sent");
        record("new-sent");
        record("old-unsent");
        em.flush();
        List<OutboxEvent> rows = mine();
        Long oldSent = idOf(rows, "old-sent");
        Long newSent = idOf(rows, "new-sent");
        Long oldUnsent = idOf(rows, "old-unsent");
        repository.markSent(oldSent, LocalDateTime.now().minusDays(2));
        repository.markSent(newSent, LocalDateTime.now());
        em.clear();

        relay.purgeSent();
        em.clear();

        assertThat(repository.existsById(oldSent)).isFalse(); // sent long ago
        assertThat(repository.existsById(newSent)).isTrue(); // sent recently
        assertThat(repository.existsById(oldUnsent)).isTrue(); // never sent - must never be dropped
    }

    private static Long idOf(List<OutboxEvent> rows, String key) {
        return rows.stream().filter(e -> e.getEventKey().equals(key)).findFirst().orElseThrow().getId();
    }
}
