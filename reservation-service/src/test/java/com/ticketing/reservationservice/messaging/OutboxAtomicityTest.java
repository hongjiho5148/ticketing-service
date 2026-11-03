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
 * 아웃박스는 이벤트와 예약이 함께 커밋되거나 함께 롤백될 때만 제 역할을 한다. 일부러 @Transactional로 하지 않았다.
 * 테스트가 관리하는 트랜잭션은 모든 것을 감싸서 서비스 자체의 트랜잭션이 롤백됐는지를 가려 버린다.
 * 여기서는 아무것도 커밋되지 않으므로(모든 경우가 커밋 전에 실패한다) 정리할 것이 없다.
 * compose MySQL을 쓰고, compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
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

    @MockitoBean private OutboxRecorder recorder; // 실패하도록 만들 수 있는 단순 mock(트랜잭션 프록시가 아님)
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private QueueServiceClient queueServiceClient;
    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void noReservationIsKeptWhenItsEventCannotBeRecordedAndTheSeatIsGivenBack() {
        long seatId = SEQ.incrementAndGet();
        long eventId = 8_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(queueServiceClient.isPassTokenValid(anyLong(), any())).thenReturn(true);
        SeatDetailResponse seat = new SeatDetailResponse(seatId, eventId, "공연", "홀", LocalDateTime.now().plusDays(10), false,
                "R", "A", 1, 1, "1열 1번", 10000, "AVAILABLE");
        when(eventServiceClient.getSeat(anyLong())).thenReturn(seat);
        when(eventServiceClient.hold(anyLong(), any())).thenReturn(seat);
        doThrow(new IllegalStateException("outbox write failed")).when(recorder).record(any(), any(), any());

        assertThatThrownBy(() -> reservationService.reserve(userId, new ReservationCreateRequest(seatId), "pass"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox write failed");

        // 예약 행도 함께 롤백됐다 ...
        assertThat(reservationRepository.countActiveSeats(userId, eventId, LocalDateTime.now())).isZero();
        // ... 그리고 event-service가 이미 잡았던 좌석은 막힌 채로 남지 않고 풀린다
        verify(eventServiceClient).release(eq(seatId), any());
    }
}
