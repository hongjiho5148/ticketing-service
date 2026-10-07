package com.ticketing.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.messaging.ReservationEventPublisher;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-user, per-event seat cap, against the compose MySQL (rolled back). Only the other
 * services are mocked - the count query is the thing worth exercising for real. Run inside the
 * compose network with DB_HOST=mysql, like order-service's tests.
 */
@SpringBootTest(properties = {"reservation.max-seats-per-event=2", "reservation.hold-expire-sweep-cron=-"})
@Transactional
class SeatLimitTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservationRepository;

    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private QueueServiceClient queueServiceClient;
    @MockitoBean private ReservationEventPublisher publisher;

    private Long userId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        long n = SEQ.incrementAndGet();
        userId = 9_000_000_000L + n % 1_000_000;
        eventId = 8_000_000_000L + n % 1_000_000;
        when(queueServiceClient.isPassTokenValid(anyLong(), any())).thenReturn(true);
    }

    private void seatsBelongTo(Long forEventId) {
        when(eventServiceClient.getSeat(anyLong())).thenAnswer(inv -> seat(inv.getArgument(0), forEventId));
        when(eventServiceClient.hold(anyLong(), any())).thenAnswer(inv -> seat(inv.getArgument(0), forEventId));
    }

    private static SeatDetailResponse seat(Long seatId, Long eventId) {
        return new SeatDetailResponse(seatId, eventId, "공연", "홀", LocalDateTime.now().plusDays(10),
                "R", "A", 1, 1, "1열 1번", 10000, "AVAILABLE");
    }

    @Test
    void refusesTheSeatAfterTheLimitAndNeverHoldsIt() {
        seatsBelongTo(eventId);
        reservationService.reserve(userId, new ReservationCreateRequest(1L), "pass");
        reservationService.reserve(userId, new ReservationCreateRequest(2L), "pass");

        assertThatThrownBy(() -> reservationService.reserve(userId, new ReservationCreateRequest(3L), "pass"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_LIMIT_EXCEEDED));
        verify(eventServiceClient, times(2)).hold(anyLong(), any());
        verify(eventServiceClient, never()).hold(eq(3L), any());
    }

    @Test
    void otherUsersAndOtherEventsDoNotCount() {
        seatsBelongTo(eventId);
        reservationService.reserve(userId, new ReservationCreateRequest(1L), "pass");
        reservationService.reserve(userId, new ReservationCreateRequest(2L), "pass");

        reservationService.reserve(userId + 1, new ReservationCreateRequest(3L), "pass");

        seatsBelongTo(eventId + 1);
        reservationService.reserve(userId, new ReservationCreateRequest(4L), "pass");
    }

    @Test
    void onlyConfirmedAndUnexpiredHoldsCount() {
        LocalDateTime now = LocalDateTime.now();
        reservationRepository.save(new Reservation(userId, 1L, eventId, now.minusMinutes(1))); // lapsed hold
        Reservation cancelled = reservationRepository.save(new Reservation(userId, 2L, eventId, now.plusMinutes(5)));
        cancelled.cancel();
        Reservation expired = reservationRepository.save(new Reservation(userId, 3L, eventId, now.plusMinutes(5)));
        expired.expire();
        assertThat(reservationRepository.countActiveSeats(userId, eventId, now)).isZero();

        reservationRepository.save(new Reservation(userId, 4L, eventId, now.plusMinutes(5))); // live hold
        Reservation paid = reservationRepository.save(new Reservation(userId, 5L, eventId, now.minusMinutes(30)));
        paid.confirm();
        assertThat(reservationRepository.countActiveSeats(userId, eventId, now)).isEqualTo(2);
    }
}
