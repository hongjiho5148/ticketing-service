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
 * 좌석이 다시 판매로 돌아가면 "취소표 알림" 대기자에게 메일을 보낸다. 좌석을 푸는 트랜잭션이 커밋된 뒤에
 * (메일이 도착했을 때 좌석이 정말로 AVAILABLE이도록) 요청 스레드 밖에서 실행한다(느린 SMTP 서버가 해제를
 * 붙잡지 못하게). 풀린 좌석 하나당 최대 waitlist-notify-per-seat명까지, 오래된 순서로 보내며, 각각 원자적
 * UPDATE로 먼저 가져가므로 동시에 일어난 해제가 같은 사람에게 두 번 메일을 보내지 않는다.
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

    /** 위의 리스너는 이것을 다른 스레드로 옮길 뿐이다. 동기적으로 구동할 수 있도록 따로 두었다. */
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
            return; // 다른 해제가 먼저 여기에 도착했다
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
