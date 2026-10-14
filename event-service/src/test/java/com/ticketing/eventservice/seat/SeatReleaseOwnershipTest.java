package com.ticketing.eventservice.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.internal.SeatInternalService;
import com.ticketing.eventservice.notification.MailService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * A late release request for a hold that is already gone must not free the hold of whoever took the seat since.
 * Runs against the compose MySQL/Redis (rolled back); the sweepers are switched off. Run inside the compose network
 * with DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {"seat.hold-expiry-sweep-cron=-", "alert.open-sweep-cron=-"})
@Transactional
class SeatReleaseOwnershipTest {

    @Autowired private SeatInternalService seatService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @PersistenceContext private EntityManager em;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private MailService mailService;

    private Seat seat;

    @BeforeEach
    void setUp() {
        Event event = eventRepository.save(new Event("해제 소유권 테스트", "홀", null, EventCategory.CONCERT,
                LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1), EventStatus.OPEN));
        seat = seatRepository.save(new Seat(event, "R", "A구역", 1, 1, 10000));
    }

    private Seat reload() {
        em.flush();
        em.clear();
        return seatRepository.findById(seat.getId()).orElseThrow();
    }

    @Test
    void staleReleaseDoesNotFreeTheNextHolder() {
        LocalDateTime first = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), first);
        seatService.release(seat.getId(), first);
        assertThat(reload().getStatus()).isEqualTo(SeatStatus.AVAILABLE);

        LocalDateTime second = LocalDateTime.now().plusMinutes(5).plusSeconds(2);
        seatService.hold(seat.getId(), second);

        // the first holder's late release (e.g. a slow expiry sweep) arrives now
        seatService.release(seat.getId(), first);

        Seat current = reload();
        assertThat(current.getStatus()).isEqualTo(SeatStatus.HOLD);
        assertThat(current.isHeldWith(second)).isTrue();

        seatService.release(seat.getId(), second);
        assertThat(reload().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void releaseWithoutAHoldIsAnUnconditionalRelease() {
        seatService.hold(seat.getId(), LocalDateTime.now().plusMinutes(5));

        seatService.release(seat.getId(), null);

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void releaseOfAHoldNeverFreesASoldSeat() {
        LocalDateTime hold = LocalDateTime.now().plusMinutes(5);
        seatService.hold(seat.getId(), hold);
        seatService.sell(seat.getId());

        seatService.release(seat.getId(), hold);

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void releaseOnAnAlreadyFreeSeatIsHarmless() {
        seatService.release(seat.getId(), LocalDateTime.now().plusMinutes(5));

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void timeStoredWithDifferentRoundingStillMatches() {
        LocalDateTime hold = LocalDateTime.of(2030, 1, 1, 12, 0, 0, 123_456_789);
        seatService.hold(seat.getId(), hold);

        // the other service's DB rounds/truncates to microseconds on its own
        seatService.release(seat.getId(), hold.truncatedTo(ChronoUnit.MICROS));

        assertThat(reload().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }
}
