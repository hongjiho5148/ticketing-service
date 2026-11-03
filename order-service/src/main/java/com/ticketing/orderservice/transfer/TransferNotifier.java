package com.ticketing.orderservice.transfer;

import com.ticketing.orderservice.notification.MailService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** "티켓을 받았어요" 메일을 요청 스레드 밖에서 보낸다. SMTP 서버가 느려도 양도가 느려지지 않는다. */
@Component
public class TransferNotifier {

    private static final Logger log = LoggerFactory.getLogger(TransferNotifier.class);

    private final MailService mailService;

    public TransferNotifier(MailService mailService) {
        this.mailService = mailService;
    }

    @Async
    public void notifyRequested(
            String toEmail, String toName, String fromName, String eventTitle, LocalDateTime eventStartAt) {
        try {
            mailService.sendTransferRequestedEmail(toEmail, toName, fromName, eventTitle, eventStartAt);
        } catch (Exception e) {
            // 요청은 이미 저장됐고 받는 사람의 양도함에 보인다. 메일은 덤이다.
            log.warn("양도 알림 발송 실패 to={}: {}", toEmail, e.getMessage());
        }
    }
}
