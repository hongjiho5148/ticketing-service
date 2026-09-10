package com.ticketing.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String backendUrl;

    public MailService(JavaMailSender mailSender, @Value("${app.backend-url}") String backendUrl) {
        this.mailSender = mailSender;
        this.backendUrl = backendUrl;
    }

    public void sendVerificationEmail(String to, String name, String token) {
        String verifyUrl = backendUrl + "/api/auth/verify-email?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[선착순 티켓팅] 이메일 인증을 완료해주세요");
        message.setText(name + "님, 아래 링크를 눌러 이메일 인증을 완료해주세요.\n\n"
                + verifyUrl
                + "\n\n이 링크는 24시간 동안 유효합니다.");
        mailSender.send(message);
    }
}
