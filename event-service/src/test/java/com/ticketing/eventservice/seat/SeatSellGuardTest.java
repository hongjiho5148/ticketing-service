package com.ticketing.eventservice.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.internal.SeatInternalService;
import com.ticketing.eventservice.notification.MailService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제는 자기가 결제한 홀드에 아직 잡혀 있는 좌석만 판매 처리할 수 있다. compose MySQL/Redis(롤백)에서
 * 실행하고 compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
 */
@SpringBootTest(properties = {"seat.hold-expiry-sweep-cron=-", "alert.open-sweep-cron=-"})
@Transactional
class SeatSellGuardTest {

    @Autowired private SeatInternalService seatService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @PersistenceContext private EntityManager em;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private MailService mailService;

    private Seat seat;

    @BeforeEach
    void setUp() {
        Event event = eventRepository.save(new Event("판매 보호 테스트", "홀", null, EventCategory.CONCERT,
                LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1), EventStatus.OPEN));
        seat = seatRepository.save(new Seat(event, "R", "A구역", 1, 1, 10000));
    }

    private Seat reload() {
        em.flush();
        em.clear();
        return seatRepository.findById(seat.getId()).orElseThrow();
    }

    @Test
    void sellsTheSeatWhileItIsStillHeldByThatHold() {
        LocalDateTime hold = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), hold);

        seatService.sell(seat.getId(), hold);

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void aLatePaymentCannotSellASeatSomeoneElseHoldsNow() {
        LocalDateTime first = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), first);
        seatService.release(seat.getId(), first); // 첫 홀드가 끝났다
        LocalDateTime second = LocalDateTime.now().plusMinutes(5).plusSeconds(2);
        seatService.hold(seat.getId(), second); // 다른 사람이 좌석을 가져갔다

        assertThatThrownBy(() -> seatService.sell(seat.getId(), first)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_HOLD_LOST));

        Seat current = reload();
        assertThat(current.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(current.isHeldWith(second)).isTrue();
    }

    @Test
    void aLatePaymentCannotSellAFreeSeatEither() {
        LocalDateTime hold = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), hold);
        seatService.release(seat.getId(), hold);

        assertThatThrownBy(() -> seatService.sell(seat.getId(), hold)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_HOLD_LOST));

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void aSeatSoldToSomeoneElseIsNeverSoldAgain() {
        LocalDateTime first = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), first);
        seatService.release(seat.getId(), first);
        LocalDateTime second = LocalDateTime.now().plusMinutes(5).plusSeconds(2);
        seatService.hold(seat.getId(), second);
        seatService.sell(seat.getId(), second);

        assertThatThrownBy(() -> seatService.sell(seat.getId(), first)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_HOLD_LOST));
    }

    @Test
    void sellingTwiceOutOfTheSameHoldIsHarmless() {
        LocalDateTime hold = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), hold);
        seatService.sell(seat.getId(), hold);

        seatService.sell(seat.getId(), hold); // 일부가 실패한 뒤 다시 시도한 결제

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void sellWithoutAHoldStaysUnconditional() {
        seatService.sell(seat.getId());

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.SOLD);
    }
}
