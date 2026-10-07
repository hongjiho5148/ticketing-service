package com.ticketing.authservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class MailServiceTest {

    private JavaMailSender sender;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        mailService = new MailService(sender, "http://localhost:8080", "pickseat@example.com");
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
    void verificationMailHasBothAnHtmlAndAPlainTextVersion() throws Exception {
        mailService.sendVerificationEmail("user@example.com", "홍길동", "abc-123");

        MimeMessage message = sent();
        Map<String, String> bodies = bodies(message);
        String link = "http://localhost:8080/api/auth/verify-email?token=abc-123";

        assertThat(message.getSubject()).isEqualTo("[픽시트] 이메일 인증을 완료해주세요");
        assertThat(bodies).containsKeys("plain", "html");
        assertThat(bodies.get("plain")).contains("홍길동님").contains(link).contains("24시간");
        assertThat(bodies.get("html"))
                .contains("이메일 인증하기")
                .contains("href=\"" + link + "\"")
                .contains("홍길동님")
                .contains("<html");
        preview("verification", bodies.get("html"));
    }

    @Test
    void theSenderShowsAsPickSeatNotTheBareAddress() throws Exception {
        mailService.sendVerificationEmail("user@example.com", "홍길동", "t");

        InternetAddress from = (InternetAddress) sent().getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("픽시트");
        assertThat(from.getAddress()).isEqualTo("pickseat@example.com");
    }

    @Test
    void userSuppliedTextCannotInjectHtml() throws Exception {
        mailService.sendVerificationEmail("user@example.com", "<script>alert('x')</script>&\"", "t");

        String html = bodies(sent()).get("html");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;");
    }

    @Test
    void escapeHandlesNullAndSpecialCharacters() {
        assertThat(MailTemplate.escape(null)).isEmpty();
        assertThat(MailTemplate.escape("a<b>&\"'")).isEqualTo("a&lt;b&gt;&amp;&quot;&#39;");
    }

    @Test
    void templateWithoutButtonOrDetailsStillRenders() {
        String html = MailTemplate.render("제목", List.of("본문"), Map.of(), null, null, null);

        assertThat(html).contains("제목").contains("본문").doesNotContain("<a href");
    }

    @Test
    void aMissingSenderAddressJustLeavesTheFromHeaderUnset() throws Exception {
        MailService noFrom = new MailService(sender, "http://localhost:8080", "");
        noFrom.sendVerificationEmail("user@example.com", "홍길동", "t");

        assertThat(sent().getFrom()).isNull();
        verify(sender).createMimeMessage();
        verify(sender).send(any(MimeMessage.class));
    }
}
