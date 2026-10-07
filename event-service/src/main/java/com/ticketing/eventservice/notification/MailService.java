package com.ticketing.eventservice.notification;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Trimmed copy of order-service's MailService - same JavaMailSender/SimpleMailMessage pattern, different mail types. */
@Service
public class MailService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final JavaMailSender mailSender;
    private final String frontendUrl;

    public MailService(JavaMailSender mailSender, @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.mailSender = mailSender;
        this.frontendUrl = frontendUrl;
    }

    public void sendTicketOpenedEmail(String to, String name, Long eventId, String eventTitle, LocalDateTime startAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[픽시트] 알림 신청하신 공연의 예매가 시작됐어요");
        message.setText(name + "님, \"" + eventTitle + "\" (" + startAt.format(DATE_FORMAT) + " 공연)의 예매가 열렸습니다.\n\n"
                + "지금 바로 예매하기: " + frontendUrl + "/events/" + eventId);
        mailSender.send(message);
    }

    public void sendSeatFreedEmail(String to, String name, Long eventId, String eventTitle) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[픽시트] 취소표가 나왔어요");
        message.setText(name + "님, \"" + eventTitle + "\" 공연에 취소표가 나왔습니다.\n\n"
                + "먼저 잡는 분이 임자예요. 지금 확인하기: " + frontendUrl + "/events/" + eventId);
        mailSender.send(message);
    }
}
