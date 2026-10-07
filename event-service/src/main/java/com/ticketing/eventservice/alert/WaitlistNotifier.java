package com.ticketing.eventservice.alert;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.authclient.dto.UserInternalResponse;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.notification.MailService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails the "취소표 알림" waitlist when seats go back on sale. Runs after the releasing transaction
 * commits (so the seat really is AVAILABLE when the mail lands) and off the request thread (so a
 * slow SMTP server can't hold up a release). At most waitlist-notify-per-seat people per freed
 * seat, oldest first, each claimed with an atomic UPDATE so concurrent releases never double-mail.
 */
@Component
public class WaitlistNotifier {

    private static final Logger log = LoggerFactory.getLogger(WaitlistNotifier.class);

    private final EventRepository eventRepository;
    private final WaitlistSubscriptionRepository subscriptionRepository;
    private final AuthServiceClient authServiceClient;
    private final MailService mailService;
    private final int notifyPerSeat;

    public WaitlistNotifier(
            EventRepository eventRepository,
            WaitlistSubscriptionRepository subscriptionRepository,
            AuthServiceClient authServiceClient,
            MailService mailService,
            @Value("${alert.waitlist-notify-per-seat}") int notifyPerSeat) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.authServiceClient = authServiceClient;
        this.mailService = mailService;
        this.notifyPerSeat = notifyPerSeat;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatsReleased(SeatsReleasedEvent released) {
        notifyWaitlist(released);
    }

    /** The listener above only moves this onto another thread; kept separate so it can be driven synchronously. */
    void notifyWaitlist(SeatsReleasedEvent released) {
        try {
            Event event = eventRepository.findById(released.eventId()).orElse(null);
            if (event == null || event.getStatus() != EventStatus.OPEN) {
                return;
            }
            List<WaitlistSubscription> waiting = subscriptionRepository.findByEventIdAndNotifiedAtIsNullOrderByIdAsc(
                    event.getId(), PageRequest.of(0, released.seatCount() * notifyPerSeat));
            for (WaitlistSubscription subscription : waiting) {
                notifyOne(event, subscription);
            }
        } catch (Exception e) {
            log.warn("취소표 알림 처리 실패 eventId={}: {}", released.eventId(), e.getMessage());
        }
    }

    private void notifyOne(Event event, WaitlistSubscription subscription) {
        if (subscriptionRepository.claim(subscription.getId(), LocalDateTime.now()) == 0) {
            return; // another release got here first
        }
        try {
            UserInternalResponse user = authServiceClient.getUser(subscription.getUserId());
            if (user.emailOptIn() && user.email() != null) {
                mailService.sendSeatFreedEmail(user.email(), user.name(), event.getId(), event.getTitle());
            }
        } catch (Exception e) {
            subscriptionRepository.release(subscription.getId());
            log.warn("취소표 알림 발송 실패 subscriptionId={}: {}", subscription.getId(), e.getMessage());
        }
    }
}
