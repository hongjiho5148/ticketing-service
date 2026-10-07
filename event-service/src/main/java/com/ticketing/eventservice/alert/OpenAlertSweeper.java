package com.ticketing.eventservice.alert;

import com.ticketing.eventservice.authclient.AuthServiceClient;
import com.ticketing.eventservice.authclient.dto.UserInternalResponse;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.notification.MailService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same cron-sweep shape as SeatHoldExpirySweeper. Once an event's openAt has passed it flips the
 * stored status UPCOMING -> OPEN, then mails the people who asked to be told. A failed send leaves
 * notifiedAt null so the next tick retries; only a sent mail or a confirmed opt-out closes it out.
 */
@Component
public class OpenAlertSweeper {

    private static final Logger log = LoggerFactory.getLogger(OpenAlertSweeper.class);

    private final EventRepository eventRepository;
    private final OpenAlertSubscriptionRepository subscriptionRepository;
    private final AuthServiceClient authServiceClient;
    private final MailService mailService;
    private final int batchSize;

    public OpenAlertSweeper(
            EventRepository eventRepository,
            OpenAlertSubscriptionRepository subscriptionRepository,
            AuthServiceClient authServiceClient,
            MailService mailService,
            @Value("${alert.open-batch-size}") int batchSize) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.authServiceClient = authServiceClient;
        this.mailService = mailService;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${alert.open-sweep-cron}")
    @Transactional
    public void sendOpenAlerts() {
        LocalDateTime now = LocalDateTime.now();
        for (Event event : eventRepository.findByStatusAndOpenAtLessThanEqual(EventStatus.UPCOMING, now)) {
            event.open();
        }

        Map<Long, Event> events = new HashMap<>();
        for (OpenAlertSubscription subscription : subscriptionRepository.findDue(now, PageRequest.of(0, batchSize))) {
            try {
                Event event = events.computeIfAbsent(
                        subscription.getEventId(), id -> eventRepository.findById(id).orElse(null));
                UserInternalResponse user = authServiceClient.getUser(subscription.getUserId());
                if (event != null && user.emailOptIn() && user.email() != null) {
                    mailService.sendTicketOpenedEmail(
                            user.email(), user.name(), event.getId(), event.getTitle(), event.getStartAt());
                }
                subscription.markNotified();
            } catch (Exception e) {
                log.warn("오픈 알림 발송 실패 subscriptionId={}: {}", subscription.getId(), e.getMessage());
            }
        }
    }
}
