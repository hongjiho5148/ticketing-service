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
 * SeatHoldExpirySweeper와 같은 크론 스윕 구조다. 공연의 openAt이 지나면 저장된 상태를 UPCOMING -> OPEN으로
 * 바꾸고, 알려 달라고 한 사람들에게 메일을 보낸다. 전송이 실패하면 notifiedAt을 null로 두어서 다음 주기에
 * 재시도한다. 메일을 보냈거나 수신 거부가 확인됐을 때만 마무리한다.
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
