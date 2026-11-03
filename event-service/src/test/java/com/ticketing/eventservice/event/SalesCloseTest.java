package com.ticketing.eventservice.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.internal.dto.EventExistsResponse;
import com.ticketing.eventservice.internal.dto.SeatDetailResponse;
import com.ticketing.eventservice.notification.MailService;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연의 좌석은 시작 두 시간 전까지만 잡을 수 있다. 이 규칙은 시계로 판정하고 저장된 상태는 그저 따라간다
 * (SalesCloseSweeper). 그래서 90분 뒤에 시작하는 공연은 상태가 뭐라고 하든 예매가 닫힌다. compose MySQL(롤백)에서
 * 실행하고 compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {"seat.hold-expiry-sweep-cron=-", "alert.open-sweep-cron=-"})
@Transactional
class SalesCloseTest {

    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private SalesCloseSweeper sweeper;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private MailService mailService;

    private Event show(String title, LocalDateTime startAt, EventStatus status) {
        return eventRepository.save(new Event(title, "홀", null, EventCategory.CONCERT, startAt,
                LocalDateTime.now().minusDays(30), status));
    }

    @Test
    void salesCloseTwoHoursBeforeTheShow() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        Event event = show("마감 시각 테스트", start, EventStatus.OPEN);

        assertThat(event.getSalesCloseAt()).isEqualTo(start.minus(Duration.ofHours(2)));
        assertThat(event.isSalesClosed(start.minusHours(2).minusMinutes(1))).isFalse();
        assertThat(event.isSalesClosed(start.minusHours(2))).isTrue(); // 마감 순간 자체가 이미 너무 늦다
        assertThat(event.isSalesClosed(start.plusMinutes(1))).isTrue();
    }

    @Test
    void aShowMarkedClosedIsClosedNoMatterTheClock() {
        Event event = show("수동 마감", LocalDateTime.now().plusDays(30), EventStatus.CLOSED);

        assertThat(event.isSalesClosed(LocalDateTime.now())).isTrue();
    }

    @Test
    void theSweeperClosesShowsWithinTheWindowAndLeavesTheOthers() {
        Event tooLate = show("곧 시작하는 공연", LocalDateTime.now().plusMinutes(90), EventStatus.OPEN);
        Event notYetOpen = show("아직 오픈 전이지만 곧 시작", LocalDateTime.now().plusMinutes(30), EventStatus.UPCOMING);
        Event finished = show("이미 끝난 공연", LocalDateTime.now().minusDays(3), EventStatus.OPEN);
        Event plenty = show("한참 남은 공연", LocalDateTime.now().plusMinutes(150), EventStatus.OPEN);
        Event farAway = show("먼 공연", LocalDateTime.now().plusDays(20), EventStatus.OPEN);

        sweeper.closeFinishedSales();

        assertThat(eventRepository.findById(tooLate.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(eventRepository.findById(notYetOpen.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(eventRepository.findById(finished.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(eventRepository.findById(plenty.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(eventRepository.findById(farAway.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.OPEN);
    }

    @Test
    void theOtherServicesAreToldFromTheClockEvenBeforeTheSweeperHasRun() {
        Event closing = show("90분 뒤 시작", LocalDateTime.now().plusMinutes(90), EventStatus.OPEN);
        Event open = show("열흘 뒤 시작", LocalDateTime.now().plusDays(10), EventStatus.OPEN);
        Seat closingSeat = seatRepository.save(new Seat(closing, "R", "A구역", 1, 1, 10000));
        Seat openSeat = seatRepository.save(new Seat(open, "R", "A구역", 1, 1, 10000));

        assertThat(EventExistsResponse.from(closing).salesClosed()).isTrue();
        assertThat(EventExistsResponse.from(open).salesClosed()).isFalse();
        assertThat(SeatDetailResponse.from(closingSeat).salesClosed()).isTrue();
        assertThat(SeatDetailResponse.from(openSeat).salesClosed()).isFalse();
    }
}
