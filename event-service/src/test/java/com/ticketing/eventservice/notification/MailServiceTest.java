package com.ticketing.eventservice.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class MailServiceTest {

    private static final LocalDateTime SHOW = LocalDateTime.of(2026, 11, 6, 19, 0);

    private JavaMailSender sender;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        mailService = new MailService(sender, "http://localhost:5173", "pickseat@example.com");
    }

    private MimeMessage sent() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        return message;
    }

    private static Map<String, String> bodies(Part part) throws Exception {
        Map<String, String> out = new HashMap<>();
        collect(part, out);
        return out;
    }

    private static void collect(Part part, Map<String, String> out) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), out);
            }
        } else if (part.isMimeType("text/plain")) {
            out.put("plain", (String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            out.put("html", (String) part.getContent());
        }
    }

    /** For eyeballing the real rendering: MAIL_PREVIEW_DIR=/some/dir makes the test leave the HTML there. */
    private static void preview(String name, String html) throws Exception {
        String dir = System.getenv("MAIL_PREVIEW_DIR");
        if (dir != null && !dir.isBlank()) {
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, name + ".html"), html);
        }
    }

    @Test
    void openAlertMailLinksStraightToTheEvent() throws Exception {
        mailService.sendTicketOpenedEmail("user@example.com", "홍길동", 28L, "AKMU 콘서트", SHOW);

        MimeMessage message = sent();
        Map<String, String> bodies = bodies(message);
        assertThat(message.getSubject()).isEqualTo("[픽시트] 알림 신청하신 공연의 예매가 시작됐어요");
        assertThat(bodies.get("plain")).contains("http://localhost:5173/events/28");
        assertThat(bodies.get("html"))
                .contains("지금 예매하기")
                .contains("href=\"http://localhost:5173/events/28\"")
                .contains("AKMU 콘서트")
                .contains("11월 6일 19:00");
        preview("open-alert", bodies.get("html"));
    }

    @Test
    void waitlistMailLinksStraightToTheEvent() throws Exception {
        mailService.sendSeatFreedEmail("user@example.com", "홍길동", 29L, "AKMU 콘서트");

        Map<String, String> bodies = bodies(sent());
        assertThat(bodies.get("plain")).contains("취소표").contains("http://localhost:5173/events/29");
        assertThat(bodies.get("html")).contains("좌석 확인하기").contains("href=\"http://localhost:5173/events/29\"");
        preview("waitlist", bodies.get("html"));
    }

    @Test
    void theSenderShowsAsPickSeatAndTitlesAreEscaped() throws Exception {
        mailService.sendSeatFreedEmail("user@example.com", "홍길동", 1L, "<script>x</script>");

        MimeMessage message = sent();
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("픽시트");
        assertThat(bodies(message).get("html")).doesNotContain("<script>").contains("&lt;script&gt;x&lt;/script&gt;");
    }
}
