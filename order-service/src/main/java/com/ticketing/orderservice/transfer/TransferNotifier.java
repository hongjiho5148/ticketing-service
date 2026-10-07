package com.ticketing.orderservice.transfer;

import com.ticketing.orderservice.notification.MailService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Sends the "you were sent a ticket" mail off the request thread, so a slow SMTP server never slows a transfer down. */
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
            // The request is already saved and shows up in the recipient's transfer box; the mail is a courtesy.
            log.warn("양도 알림 발송 실패 to={}: {}", toEmail, e.getMessage());
        }
    }
}
