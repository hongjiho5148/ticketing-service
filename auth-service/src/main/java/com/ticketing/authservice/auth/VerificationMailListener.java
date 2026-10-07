package com.ticketing.authservice.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the verification mail off the request thread. SMTP takes seconds, and signup used to wait
 * for it (and to fail whole if it failed). Running after the commit means the link in the mail
 * always points at a user that really exists, and no mail goes out for a signup that rolled back.
 * A send failure is logged here; the user can ask for a fresh mail with POST /api/auth/resend-verification.
 */
@Component
public class VerificationMailListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailListener.class);

    private final MailService mailService;

    public VerificationMailListener(MailService mailService) {
        this.mailService = mailService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationMailRequested(VerificationMailRequested event) {
        try {
            mailService.sendVerificationEmail(event.to(), event.name(), event.token());
        } catch (Exception e) {
            log.error("인증 메일 발송 실패 to={}: {}", event.to(), e.getMessage());
        }
    }
}
