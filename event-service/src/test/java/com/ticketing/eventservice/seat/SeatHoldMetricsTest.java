package com.ticketing.eventservice.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.internal.SeatInternalService;
import com.ticketing.eventservice.notification.MailService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 좌석 잡기는 결과별로 센다. 충돌(많은 사람이 같은 좌석에 몰림)이 늘면 대시보드에 보인다. */
@SpringBootTest(properties = {"seat.hold-expiry-sweep-cron=-", "alert.open-sweep-cron=-"})
@Transactional
class SeatHoldMetricsTest {

    @Autowired private SeatInternalService seatService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private MeterRegistry meters;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private MailService mailService;

    private double holds(String result) {
        return meters.counter("seat_hold_total", "result", result).count();
    }

    @Test
    void aSuccessfulHoldAndAConflictAreCountedSeparately() {
        Event event = eventRepository.save(new Event("홀드 지표 테스트", "홀", null, EventCategory.CONCERT,
                LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1), EventStatus.OPEN));
        Seat seat = seatRepository.save(new Seat(event, "R", "A구역", 1, 1, 10000));
        double okBefore = holds("ok");
        double conflictBefore = holds("conflict");

        seatService.hold(seat.getId(), LocalDateTime.now().plusMinutes(5));
        assertThatThrownBy(() -> seatService.hold(seat.getId(), LocalDateTime.now().plusMinutes(5)))
                .isInstanceOf(ApiException.class);

        assertThat(holds("ok")).isEqualTo(okBefore + 1);
        assertThat(holds("conflict")).isEqualTo(conflictBefore + 1);
    }
}
