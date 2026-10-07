package com.ticketing.eventservice.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Copy of auth-service's MailService with this service's mail types. Sends multipart/alternative: an HTML
 * version (see MailTemplate) plus a plain-text one for clients that don't render HTML.
 */
@Service
public class MailService {

    private static final String SENDER_NAME = "픽시트";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final JavaMailSender mailSender;
    private final String frontendUrl;
    private final String from;

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
            @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.frontendUrl = frontendUrl;
        this.from = from;
    }

    public void sendTicketOpenedEmail(String to, String name, Long eventId, String eventTitle, LocalDateTime startAt) {
        String url = frontendUrl + "/events/" + eventId;
        String when = startAt.format(DATE_FORMAT);
        String plain = name + "님, \"" + eventTitle + "\" (" + when + " 공연)의 예매가 열렸습니다.\n\n지금 바로 예매하기: " + url;
        String html = MailTemplate.render(
                "알림 신청하신 공연의 예매가 시작됐어요",
                List.of(name + "님, 기다리시던 공연의 예매가 열렸습니다.", "좌석은 먼저 잡는 분께 돌아가니 서둘러 확인해 보세요."),
                details("공연", eventTitle, "공연일", when),
                "지금 예매하기",
                url,
                null);
        send(to, "[픽시트] 알림 신청하신 공연의 예매가 시작됐어요", plain, html);
    }

    public void sendSeatFreedEmail(String to, String name, Long eventId, String eventTitle) {
        String url = frontendUrl + "/events/" + eventId;
        String plain = name + "님, \"" + eventTitle + "\" 공연에 취소표가 나왔습니다.\n\n먼저 잡는 분이 임자예요. 지금 확인하기: " + url;
        String html = MailTemplate.render(
                "취소표가 나왔어요",
                List.of(name + "님, 알림 신청하신 공연에 취소표가 나왔습니다.", "먼저 잡는 분이 임자예요. 지금 확인해 보세요."),
                details("공연", eventTitle),
                "좌석 확인하기",
                url,
                null);
        send(to, "[픽시트] 취소표가 나왔어요", plain, html);
    }

    /** Alternating label, value, label, value ... in the order given. */
    private static Map<String, String> details(String... labelsAndValues) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (int i = 0; i + 1 < labelsAndValues.length; i += 2) {
            rows.put(labelsAndValues[i], labelsAndValues[i + 1]);
        }
        return rows;
    }

    private void send(String to, String subject, String plain, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (from != null && !from.isBlank()) {
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
