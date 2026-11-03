package com.ticketing.authservice.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 인증 메일을 요청 스레드 밖에서 보낸다. SMTP는 몇 초가 걸리고, 예전에는 가입이 그것을 기다렸다가
 * (실패하면 가입 전체가 실패했다). 커밋 뒤에 실행하므로 메일의 링크는 항상 실제로 존재하는 사용자를 가리키고,
 * 롤백된 가입에 대해서는 메일이 나가지 않는다. 전송 실패는 여기서 로그로 남기며,
 * 사용자는 POST /api/auth/resend-verification으로 새 메일을 요청할 수 있다.
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
