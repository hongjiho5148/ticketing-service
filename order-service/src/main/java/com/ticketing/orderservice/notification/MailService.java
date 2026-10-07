package com.ticketing.orderservice.notification;

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
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.frontendUrl = frontendUrl;
        this.from = from;
    }

    public void sendTicketIssuedEmail(String to, String name, String eventTitle) {
        String url = frontendUrl + "/tickets";
        String plain = name + "님, \"" + eventTitle + "\" 공연의 입장권 QR이 발급됐습니다.\n\n"
                + "내 티켓함에서 확인하실 수 있어요. 공연 당일 입구에서 QR을 보여주시면 바로 입장 가능합니다.\n" + url;
        String html = MailTemplate.render(
                "입장권 QR이 발급됐어요",
                List.of(name + "님, 공연 입장권 QR이 발급됐습니다.", "공연 당일 입구에서 QR을 보여주시면 바로 입장할 수 있어요."),
                details("공연", eventTitle),
                "내 티켓함 열기",
                url,
                null);
        send(to, "[픽시트] 입장권 QR이 발급됐어요", plain, html);
    }

    public void sendShowReminderEmail(String to, String name, String eventTitle, LocalDateTime eventStartAt) {
        String url = frontendUrl + "/tickets";
        String when = eventStartAt.format(DATE_FORMAT);
        String plain = name + "님, \"" + eventTitle + "\" 공연이 " + when + "에 시작합니다.\n\n"
                + "공연 시작 2시간 전부터 내 티켓함에서 입장권 QR을 받으실 수 있어요.\n" + url;
        String html = MailTemplate.render(
                "내일 공연이 있어요",
                List.of(name + "님, 예매하신 공연이 곧 시작돼요.", "공연 시작 2시간 전부터 내 티켓함에서 입장권 QR을 받으실 수 있어요."),
                details("공연", eventTitle, "일시", when),
                "내 티켓함 열기",
                url,
                null);
        send(to, "[픽시트] 내일 공연이 있어요", plain, html);
    }

    public void sendTransferRequestedEmail(
            String to, String name, String fromName, String eventTitle, LocalDateTime eventStartAt) {
        String url = frontendUrl + "/transfers";
        String when = eventStartAt.format(DATE_FORMAT);
        String plain = name + "님, " + fromName + "님이 \"" + eventTitle + "\" (" + when + ") 티켓을 보내셨어요.\n\n"
                + "로그인 후 양도함에서 수락하면 내 티켓이 됩니다. 거절하면 보낸 분께 그대로 남아요.\n" + url;
        String html = MailTemplate.render(
                fromName + "님이 티켓을 양도했어요",
                List.of(name + "님, " + fromName + "님이 티켓을 보내셨어요.", "수락하면 내 티켓이 되고, 거절하면 보낸 분께 그대로 남아요."),
                details("공연", eventTitle, "일시", when, "보낸 사람", fromName),
                "양도함 열기",
                url,
                "공연 시작 2시간 전까지만 수락할 수 있어요.");
        send(to, "[픽시트] " + fromName + "님이 티켓을 양도했어요", plain, html);
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
