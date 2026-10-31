package com.ticketing.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seats can only be taken until 2 hours before the show starts. event-service decides that (salesClosed in the seat it
 * returns, judged from its clock); the reservation side must refuse before holding anything. Against the compose MySQL
 * (rolled back); run inside the compose network with DB_HOST=mysql.
 */
@SpringBootTest(properties = {"reservation.hold-expire-sweep-cron=-"})
@Transactional
class SalesClosedTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private ReservationService reservationService;

    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private QueueServiceClient queueServiceClient;

    private static SeatDetailResponse seat(long seatId, boolean salesClosed) {
        return new SeatDetailResponse(seatId, 8_100_000_000L, "공연", "홀", LocalDateTime.now().plusHours(1), salesClosed,
                "R", "A", 1, 1, "1열 1번", 10000, "AVAILABLE");
    }

    @Test
    void aSeatOfAShowWhoseSalesAreOverIsRefusedWithoutHoldingAnything() {
        long userId = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(queueServiceClient.isPassTokenValid(anyLong(), any())).thenReturn(true);
        when(eventServiceClient.getSeat(anyLong())).thenReturn(seat(1L, true));

        assertThatThrownBy(() -> reservationService.reserve(userId, new ReservationCreateRequest(1L), "pass"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SALES_CLOSED);
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(410);
                });
        verify(eventServiceClient, never()).hold(anyLong(), any());
    }

    @Test
    void aSeatOfAShowStillOnSaleIsHeldAsBefore() {
        long userId = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(queueServiceClient.isPassTokenValid(anyLong(), any())).thenReturn(true);
        when(eventServiceClient.getSeat(anyLong())).thenReturn(seat(2L, false));
        when(eventServiceClient.hold(anyLong(), any())).thenReturn(seat(2L, false));

        assertThat(reservationService.reserve(userId, new ReservationCreateRequest(2L), "pass")).isNotNull();
    }
}
