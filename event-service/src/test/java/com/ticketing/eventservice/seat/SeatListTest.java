package com.ticketing.eventservice.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventService;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.event.dto.EventDetailResponse;
import com.ticketing.eventservice.event.dto.SeatGradeSummary;
import com.ticketing.eventservice.event.dto.SeatSectionSummary;
import com.ticketing.eventservice.internal.SeatInternalService;
import com.ticketing.eventservice.notification.MailService;
import com.ticketing.eventservice.seat.dto.SeatResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seat list and the detail page's seat summaries are computed by the database (a projection and GROUP BY queries)
 * instead of loading every Seat entity of the event, so these pin down that the numbers still come out right with
 * seats in every status. Against the compose MySQL/Redis (rolled back); run inside the compose network with
 * DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {"seat.hold-expiry-sweep-cron=-", "alert.open-sweep-cron=-"})
@Transactional
class SeatListTest {

    @Autowired private EventService eventService;
    @Autowired private SeatInternalService seatService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @PersistenceContext private EntityManager em;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private MailService mailService;

    private Event event;
    private Seat heldSeat;
    private Seat soldSeat;

    @BeforeEach
    void setUp() {
        event = eventRepository.save(new Event("좌석 목록 테스트", "홀", null, EventCategory.CONCERT,
                LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1), EventStatus.OPEN));
        // 구역 A(R석, 10000원) 4석, 구역 B(S석, 5000원) 2석
        heldSeat = seatRepository.save(new Seat(event, "R", "A구역", 1, 1, 10000));
        seatRepository.save(new Seat(event, "R", "A구역", 1, 2, 10000));
        seatRepository.save(new Seat(event, "R", "A구역", 2, 1, 10000));
        seatRepository.save(new Seat(event, "R", "A구역", 2, 2, 10000));
        soldSeat = seatRepository.save(new Seat(event, "S", "B구역", 1, 1, 5000));
        seatRepository.save(new Seat(event, "S", "B구역", 1, 2, 5000));

        seatService.hold(heldSeat.getId(), LocalDateTime.now().plusMinutes(5));
        LocalDateTime soldHold = LocalDateTime.now().plusMinutes(5);
        seatService.hold(soldSeat.getId(), soldHold);
        seatService.sell(soldSeat.getId(), soldHold);
        em.flush();
        em.clear();
    }

    @Test
    void listsEverySeatOfTheEventInIdOrderWithItsCurrentStatus() {
        List<SeatResponse> seats = eventService.listSeats(event.getId(), null);

        assertThat(seats).hasSize(6);
        assertThat(seats).extracting(SeatResponse::id).isSorted();
        SeatResponse held = seats.stream().filter(s -> s.id().equals(heldSeat.getId())).findFirst().orElseThrow();
        assertThat(held.status()).isEqualTo(SeatStatus.HOLD);
        assertThat(held.grade()).isEqualTo("R");
        assertThat(held.section()).isEqualTo("A구역");
        assertThat(held.rowNo()).isEqualTo(1);
        assertThat(held.seatNumber()).isEqualTo(1);
        assertThat(held.seatNo()).isEqualTo("1열 1번");
        assertThat(held.price()).isEqualTo(10000);
        assertThat(seats.stream().filter(s -> s.status() == SeatStatus.SOLD)).extracting(SeatResponse::id)
                .containsExactly(soldSeat.getId());
        assertThat(seats.stream().filter(s -> s.status() == SeatStatus.AVAILABLE)).hasSize(4);
    }

    @Test
    void filtersByGrade() {
        assertThat(eventService.listSeats(event.getId(), "S")).hasSize(2).allMatch(s -> s.grade().equals("S"));
        assertThat(eventService.listSeats(event.getId(), "R")).hasSize(4);
        assertThat(eventService.listSeats(event.getId(), "VIP")).isEmpty();
    }

    @Test
    void anUnknownEventIsNotFound() {
        assertThatThrownBy(() -> eventService.listSeats(Long.MAX_VALUE, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    void detailSummariesCountSeatsPerGradeAndSectionAndOnlyAvailableOnesAsAvailable() {
        EventDetailResponse detail = eventService.getEventDetail(event.getId());

        assertThat(detail.seatSummary()).containsExactly(
                new SeatGradeSummary("R", 4, 3), // one of the four R seats is held
                new SeatGradeSummary("S", 2, 1)); // one of the two S seats is sold
        assertThat(detail.sectionSummary()).containsExactly(
                new SeatSectionSummary("A구역", "R", 10000, 4, 3),
                new SeatSectionSummary("B구역", "S", 5000, 2, 1));
    }

    @Test
    void detailOfAnEventWithoutSeatsHasEmptySummaries() {
        Event empty = eventRepository.save(new Event("좌석 없는 공연", "홀", null, EventCategory.CONCERT,
                LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1), EventStatus.OPEN));

        EventDetailResponse detail = eventService.getEventDetail(empty.getId());

        assertThat(detail.seatSummary()).isEmpty();
        assertThat(detail.sectionSummary()).isEmpty();
    }
}
