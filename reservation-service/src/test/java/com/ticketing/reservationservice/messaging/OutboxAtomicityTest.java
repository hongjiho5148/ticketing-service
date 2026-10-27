package com.ticketing.reservationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.ReservationRepository;
import com.ticketing.reservationservice.reservation.ReservationService;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The outbox only does its job if the event and the reservation commit or roll back together. Deliberately NOT
 * @Transactional: a test-managed transaction would wrap everything and hide whether the service's own transaction
 * rolled back. Nothing is ever committed here (every case fails before the commit), so there is nothing to clean up.
 * Compose MySQL; run inside the compose network with DB_HOST=mysql.
 */
@SpringBootTest(properties = {
    "reservation.hold-expire-sweep-cron=-",
    "outbox.relay-initial-delay-ms=3600000",
    "outbox.purge-cron=-"
})
class OutboxAtomicityTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;

    @MockitoBean private OutboxRecorder recorder; // a plain mock (not the transactional proxy), so it can be made to fail
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private QueueServiceClient queueServiceClient;
    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void noReservationIsKeptWhenItsEventCannotBeRecordedAndTheSeatIsGivenBack() {
        long seatId = SEQ.incrementAndGet();
        long eventId = 8_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(queueServiceClient.isPassTokenValid(anyLong(), any())).thenReturn(true);
        SeatDetailResponse seat = new SeatDetailResponse(seatId, eventId, "공연", "홀", LocalDateTime.now().plusDays(10),
                "R", "A", 1, 1, "1열 1번", 10000, "AVAILABLE");
        when(eventServiceClient.getSeat(anyLong())).thenReturn(seat);
        when(eventServiceClient.hold(anyLong(), any())).thenReturn(seat);
        doThrow(new IllegalStateException("outbox write failed")).when(recorder).record(any(), any(), any());

        assertThatThrownBy(() -> reservationService.reserve(userId, new ReservationCreateRequest(seatId), "pass"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox write failed");

        // the reservation row was rolled back with it ...
        assertThat(reservationRepository.countActiveSeats(userId, eventId, LocalDateTime.now())).isZero();
        // ... and the seat that event-service had already held is released rather than left stuck
        verify(eventServiceClient).release(eq(seatId), any());
    }
}
