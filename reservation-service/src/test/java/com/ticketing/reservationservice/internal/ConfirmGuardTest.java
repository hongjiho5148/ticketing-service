package com.ticketing.reservationservice.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.messaging.ReservationEventPublisher;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.Reservation;
import com.ticketing.reservationservice.reservation.ReservationRepository;
import com.ticketing.reservationservice.reservation.ReservationStatus;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirming a reservation (what a successful payment triggers) only works while the hold is alive. Against the
 * compose MySQL (rolled back); event-service is mocked. Run inside the compose network with DB_HOST=mysql.
 */
@SpringBootTest(properties = {"reservation.hold-expire-sweep-cron=-"})
@Transactional
class ConfirmGuardTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private ReservationInternalService internalService;
    @Autowired private ReservationRepository reservationRepository;

    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private QueueServiceClient queueServiceClient;
    @MockitoBean private ReservationEventPublisher publisher;

    private Reservation reservation(ReservationStatus status, LocalDateTime holdExpireAt) {
        long n = SEQ.incrementAndGet();
        Reservation r = new Reservation(9_000_000_000L + n % 1_000_000, n, 8_000_000_000L + n % 1_000_000, holdExpireAt);
        if (status == ReservationStatus.EXPIRED) r.expire();
        if (status == ReservationStatus.CANCELLED) r.cancel();
        if (status == ReservationStatus.CONFIRMED) r.confirm();
        return reservationRepository.save(r);
    }

    @Test
    void confirmsAHoldingReservationAndSellsTheSeatOutOfThatHold() {
        LocalDateTime hold = LocalDateTime.now().plusMinutes(3);
        Reservation r = reservation(ReservationStatus.HOLDING, hold);

        internalService.confirm(r.getId());

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(eventServiceClient).sell(r.getSeatId(), r.getHoldExpireAt());
    }

    @Test
    void anExpiredReservationCannotBeConfirmedAndTheSeatIsNotTouched() {
        Reservation r = reservation(ReservationStatus.EXPIRED, LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> internalService.confirm(r.getId())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.HOLD_EXPIRED));

        verify(eventServiceClient, never()).sell(anyLong(), any());
    }

    @Test
    void aCancelledReservationCannotBeConfirmedEither() {
        Reservation r = reservation(ReservationStatus.CANCELLED, LocalDateTime.now().plusMinutes(1));

        assertThatThrownBy(() -> internalService.confirm(r.getId())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.HOLD_EXPIRED));
    }

    @Test
    void confirmingTwiceIsHarmlessAndDoesNotSellAgain() {
        Reservation r = reservation(ReservationStatus.CONFIRMED, LocalDateTime.now().plusMinutes(3));

        internalService.confirm(r.getId());

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(eventServiceClient, never()).sell(anyLong(), any());
    }
}
