package com.ticketing.authservice.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends the mails as multipart/alternative: an HTML version (see MailTemplate) for normal clients and a
 * plain-text version for the ones that don't render HTML, which also keeps spam filters happier.
 */
@Service
public class MailService {

    private static final String SENDER_NAME = "픽시트";

    private final JavaMailSender mailSender;
    private final String backendUrl;
    private final String from;

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.backend-url}") String backendUrl,
            @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.backendUrl = backendUrl;
        this.from = from;
    }

    public void sendVerificationEmail(String to, String name, String token) {
        String verifyUrl = backendUrl + "/api/auth/verify-email?token=" + token;
        String note = "이 링크는 24시간 동안 유효합니다. 본인이 가입하지 않았다면 이 메일은 무시하셔도 됩니다.";

        String plain = name + "님, 아래 링크를 눌러 이메일 인증을 완료해주세요.\n\n"
                + verifyUrl
                + "\n\n" + note;
        String html = MailTemplate.render(
                "이메일 인증을 완료해주세요",
                List.of(name + "님, 픽시트에 가입해 주셔서 감사합니다.", "아래 버튼을 눌러 이메일 인증을 마치면 바로 로그인할 수 있어요."),
                Map.of(),
                "이메일 인증하기",
                verifyUrl,
                note);
        send(to, "[픽시트] 이메일 인증을 완료해주세요", plain, html);
    }

    private void send(String to, String subject, String plain, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (from != null && !from.isBlank()) {
                // Shows "픽시트" instead of the bare Gmail address in the inbox list.
                helper.setFrom(new InternetAddress(from, SENDER_NAME, "UTF-8"));
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plain, html);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("메일을 만들지 못했습니다: " + e.getMessage(), e);
        }
        mailSender.send(message);
    }
}
