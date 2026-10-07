package com.ticketing.orderservice.notification;

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
    void ticketIssuedMailLinksToTheTicketBox() throws Exception {
        mailService.sendTicketIssuedEmail("user@example.com", "홍길동", "AKMU 콘서트");

        MimeMessage message = sent();
        Map<String, String> bodies = bodies(message);
        assertThat(message.getSubject()).isEqualTo("[픽시트] 입장권 QR이 발급됐어요");
        assertThat(bodies.get("plain")).contains("AKMU 콘서트").contains("http://localhost:5173/tickets");
        assertThat(bodies.get("html"))
                .contains("내 티켓함 열기")
                .contains("href=\"http://localhost:5173/tickets\"")
                .contains("AKMU 콘서트");
        preview("ticket-issued", bodies.get("html"));
    }

    @Test
    void reminderMailShowsTheEventAndWhen() throws Exception {
        mailService.sendShowReminderEmail("user@example.com", "홍길동", "AKMU 콘서트", SHOW);

        Map<String, String> bodies = bodies(sent());
        assertThat(bodies.get("plain")).contains("11월 6일 19:00");
        assertThat(bodies.get("html")).contains("AKMU 콘서트").contains("11월 6일 19:00").contains("일시");
        preview("reminder", bodies.get("html"));
    }

    @Test
    void transferMailNamesTheSenderAndLinksToTheTransferBox() throws Exception {
        mailService.sendTransferRequestedEmail("user@example.com", "받는사람", "보내는사람", "AKMU 콘서트", SHOW);

        MimeMessage message = sent();
        Map<String, String> bodies = bodies(message);
        assertThat(message.getSubject()).isEqualTo("[픽시트] 보내는사람님이 티켓을 양도했어요");
        assertThat(bodies.get("html"))
                .contains("보내는사람님이 티켓을 양도했어요")
                .contains("양도함 열기")
                .contains("href=\"http://localhost:5173/transfers\"")
                .contains("2시간 전까지만");
        assertThat(bodies.get("plain")).contains("보내는사람님").contains("http://localhost:5173/transfers");
        preview("transfer", bodies.get("html"));
    }

    @Test
    void theSenderShowsAsPickSeatAndTitlesAreEscaped() throws Exception {
        mailService.sendTicketIssuedEmail("user@example.com", "<b>이름</b>", "<img src=x onerror=alert(1)>");

        MimeMessage message = sent();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("픽시트");

        String html = bodies(message).get("html");
        assertThat(html).doesNotContain("<img").doesNotContain("<b>이름");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
    }
}
