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
 * 좌석은 공연 시작 2시간 전까지만 잡을 수 있다. 이를 판정하는 것은 event-service다(돌려주는 좌석의 salesClosed,
 * 자기 시계로 판정). 예약 쪽은 아무것도 잡기 전에 거절해야 한다. compose MySQL(롤백)에서 실행하고
 * compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
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
