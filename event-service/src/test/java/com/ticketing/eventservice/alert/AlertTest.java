package com.ticketing.eventservice.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.authclient.dto.UserInternalResponse;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.notification.MailService;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open-alert and cancellation-ticket alert logic against the compose MySQL/Redis (rolled back).
 * Auth lookups and mail are mocked; the cron sweepers are switched off and driven by hand. Run
 * inside the compose network with DB_HOST=mysql REDIS_HOST=redis, like order-service's tests.
 */
@SpringBootTest(properties = {
    "seat.hold-expiry-sweep-cron=-",
    "alert.open-sweep-cron=-",
    "alert.waitlist-notify-per-seat=1"
})
@Transactional
class AlertTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private AlertService alertService;
    @Autowired private OpenAlertSweeper openAlertSweeper;
    @Autowired private WaitlistNotifier waitlistNotifier;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private OpenAlertSubscriptionRepository openAlertRepository;
    @Autowired private WaitlistSubscriptionRepository waitlistRepository;
    @PersistenceContext private EntityManager em;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private MailService mailService;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(authServiceClient.getUser(anyLong())).thenAnswer(inv ->
                new UserInternalResponse(inv.getArgument(0), "u" + inv.getArgument(0) + "@example.com", "테스터", true));
    }

    private Event event(EventStatus status, LocalDateTime openAt) {
        return eventRepository.save(new Event("알림 테스트", "홀", null, EventCategory.CONCERT,
                LocalDateTime.now().plusDays(30), openAt, status));
    }

    private Seat seat(Event event, int number) {
        return seatRepository.save(new Seat(event, "R", "A구역", 1, number, 10000));
    }

    private void expectError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void openAlertOnlyForEventsThatHaveNotOpenedYet() {
        Event upcoming = event(EventStatus.UPCOMING, LocalDateTime.now().plusDays(1));
        Event open = event(EventStatus.OPEN, LocalDateTime.now().minusDays(1));

        assertThat(alertService.subscribeOpenAlert(userId, upcoming.getId()).subscribed()).isTrue();
        assertThat(alertService.openAlertStatus(userId, upcoming.getId()).subscribed()).isTrue();
        expectError(() -> alertService.subscribeOpenAlert(userId, open.getId()), ErrorCode.OPEN_ALERT_NOT_AVAILABLE);

        alertService.unsubscribeOpenAlert(userId, upcoming.getId());
        assertThat(alertService.openAlertStatus(userId, upcoming.getId()).subscribed()).isFalse();
    }

    @Test
    void subscribingTwiceKeepsOneRow() {
        Event upcoming = event(EventStatus.UPCOMING, LocalDateTime.now().plusDays(1));
        alertService.subscribeOpenAlert(userId, upcoming.getId());
        alertService.subscribeOpenAlert(userId, upcoming.getId());
        em.flush();
        assertThat(openAlertRepository.findAll().stream().filter(s -> s.getUserId().equals(userId))).hasSize(1);
    }

    @Test
    void sweeperOpensTheEventAndMailsSubscribersOnce() {
        Event due = event(EventStatus.UPCOMING, LocalDateTime.now().minusMinutes(1));
        openAlertRepository.save(new OpenAlertSubscription(userId, due.getId()));

        openAlertSweeper.sendOpenAlerts();
        openAlertSweeper.sendOpenAlerts(); // a second tick must not mail again

        assertThat(due.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(openAlertRepository.findByUserIdAndEventId(userId, due.getId()).orElseThrow().getNotifiedAt())
                .isNotNull();
        verify(mailService, times(1)).sendTicketOpenedEmail(
                anyString(), anyString(), eq(due.getId()), anyString(), any());
    }

    @Test
    void sweeperLeavesFutureEventsAndRetriesAfterAFailedLookup() {
        Event future = event(EventStatus.UPCOMING, LocalDateTime.now().plusDays(1));
        Event due = event(EventStatus.UPCOMING, LocalDateTime.now().minusMinutes(1));
        openAlertRepository.save(new OpenAlertSubscription(userId, future.getId()));
        openAlertRepository.save(new OpenAlertSubscription(userId + 1, due.getId()));
        when(authServiceClient.getUser(userId + 1)).thenThrow(new IllegalStateException("auth down"));

        openAlertSweeper.sendOpenAlerts();

        assertThat(future.getStatus()).isEqualTo(EventStatus.UPCOMING);
        assertThat(openAlertRepository.findByUserIdAndEventId(userId, future.getId()).orElseThrow().getNotifiedAt())
                .isNull();
        assertThat(openAlertRepository.findByUserIdAndEventId(userId + 1, due.getId()).orElseThrow().getNotifiedAt())
                .isNull(); // still pending - next tick retries
        verify(mailService, never()).sendTicketOpenedEmail(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void optedOutUserIsClosedOutWithoutAMail() {
        Event due = event(EventStatus.UPCOMING, LocalDateTime.now().minusMinutes(1));
        openAlertRepository.save(new OpenAlertSubscription(userId, due.getId()));
        when(authServiceClient.getUser(userId)).thenReturn(new UserInternalResponse(userId, "x@example.com", "테스터", false));

        openAlertSweeper.sendOpenAlerts();

        assertThat(openAlertRepository.findByUserIdAndEventId(userId, due.getId()).orElseThrow().getNotifiedAt())
                .isNotNull();
        verify(mailService, never()).sendTicketOpenedEmail(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void waitlistOnlyWhenNothingIsAvailable() {
        Event open = event(EventStatus.OPEN, LocalDateTime.now().minusDays(1));
        Seat seat = seat(open, 1);

        expectError(() -> alertService.subscribeWaitlist(userId, open.getId()), ErrorCode.WAITLIST_NOT_AVAILABLE);

        seat.sell();
        em.flush();
        assertThat(alertService.subscribeWaitlist(userId, open.getId()).subscribed()).isTrue();
    }

    @Test
    void aFreedSeatNotifiesOnlyTheOldestWaitingUsers() {
        Event open = event(EventStatus.OPEN, LocalDateTime.now().minusDays(1));
        WaitlistSubscription first = waitlistRepository.save(new WaitlistSubscription(userId, open.getId()));
        WaitlistSubscription second = waitlistRepository.save(new WaitlistSubscription(userId + 1, open.getId()));
        em.flush();

        waitlistNotifier.notifyWaitlist(new SeatsReleasedEvent(open.getId(), 1)); // notify-per-seat = 1

        em.clear();
        assertThat(waitlistRepository.findById(first.getId()).orElseThrow().getNotifiedAt()).isNotNull();
        assertThat(waitlistRepository.findById(second.getId()).orElseThrow().getNotifiedAt()).isNull();
        verify(mailService, times(1)).sendSeatFreedEmail(anyString(), anyString(), eq(open.getId()), anyString());

        waitlistNotifier.notifyWaitlist(new SeatsReleasedEvent(open.getId(), 1)); // next freed seat -> next in line
        verify(mailService, times(2)).sendSeatFreedEmail(anyString(), anyString(), eq(open.getId()), anyString());
    }

    @Test
    void aFailedMailPutsTheUserBackInLine() {
        Event open = event(EventStatus.OPEN, LocalDateTime.now().minusDays(1));
        WaitlistSubscription sub = waitlistRepository.save(new WaitlistSubscription(userId, open.getId()));
        em.flush();
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(mailService).sendSeatFreedEmail(anyString(), anyString(), anyLong(), anyString());

        waitlistNotifier.notifyWaitlist(new SeatsReleasedEvent(open.getId(), 1));

        em.clear();
        assertThat(waitlistRepository.findById(sub.getId()).orElseThrow().getNotifiedAt()).isNull();
    }

    @Test
    void closedEventsDoNotNotify() {
        Event closed = event(EventStatus.CLOSED, LocalDateTime.now().minusDays(2));
        waitlistRepository.save(new WaitlistSubscription(userId, closed.getId()));
        em.flush();

        waitlistNotifier.notifyWaitlist(new SeatsReleasedEvent(closed.getId(), 1));

        verify(mailService, never()).sendSeatFreedEmail(anyString(), anyString(), anyLong(), anyString());
    }
}
