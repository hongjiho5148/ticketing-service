package com.ticketing.orderservice.notification;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Trimmed copy of auth-service's MailService - same JavaMailSender/SimpleMailMessage pattern, different mail types. */
@Service
public class MailService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final JavaMailSender mailSender;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendTicketIssuedEmail(String to, String name, String eventTitle) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[픽시트] 입장권 QR이 발급됐어요");
        message.setText(name + "님, \"" + eventTitle + "\" 공연의 입장권 QR이 발급됐습니다.\n\n"
                + "마이페이지 > 내 티켓함에서 확인하실 수 있어요. 공연 당일 입구에서 QR을 보여주시면 바로 입장 가능합니다.");
        mailSender.send(message);
    }

    public void sendShowReminderEmail(String to, String name, String eventTitle, LocalDateTime eventStartAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[픽시트] 내일 공연이 있어요");
        message.setText(name + "님, \"" + eventTitle + "\" 공연이 " + eventStartAt.format(DATE_FORMAT) + "에 시작합니다.\n\n"
                + "공연 시작 2시간 전부터 마이페이지 > 내 티켓함에서 입장권 QR을 받으실 수 있어요.");
        mailSender.send(message);
    }
}
