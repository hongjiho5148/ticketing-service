package com.ticketing.authservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ticketing.authservice.auth.dto.SignupRequest;
import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.user.AuthProvider;
import com.ticketing.authservice.user.User;
import com.ticketing.authservice.user.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 인증 메일은 가입 트랜잭션이 커밋된 뒤 다른 스레드에서 나간다. 실제 커밋에 달려 있으므로 이 테스트는
 * 일부러 @Transactional로 하지 않았다. compose MySQL에 실제 사용자를 만들고 다시 지운다.
 * compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
// 가짜 OAuth id다. 실제 값은 .env에서 오고, Spring은 값이 하나도 없으면 OAuth2 클라이언트 설정을 만들지 않는다.
@SpringBootTest(properties = {
    "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test", "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test"
})
class VerificationMailTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;

    @MockitoBean private MailService mailService;

    private String email;

    private String newEmail() {
        email = "async-mail-" + UUID.randomUUID() + "@example.com";
        return email;
    }

    @AfterEach
    void cleanUp() {
        if (email != null) {
            userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).ifPresent(userRepository::delete);
        }
    }

    private void signup(String address) {
        authService.signup(new SignupRequest(address, "Test1234!@", "비동기테스트"));
    }

    @Test
    void signupDoesNotWaitForTheMailServer() {
        // 새 JVM에서 첫 가입은 클래스 로딩, 커넥션 풀, BCrypt 워밍업(컨테이너에서는 몇 초)의 비용을 낸다.
        // 그것은 측정하려는 것이 아니므로 먼저 치워 둔다.
        String warmUp = "async-mail-warmup-" + UUID.randomUUID() + "@example.com";
        signup(warmUp);
        userRepository.findByProviderAndEmail(AuthProvider.LOCAL, warmUp).ifPresent(userRepository::delete);

        doAnswer(inv -> {
            Thread.sleep(2000); // 느린 SMTP 서버
            return null;
        }).when(mailService).sendVerificationEmail(anyString(), anyString(), anyString());

        long start = System.nanoTime();
        signup(newEmail());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1500); // 가입이 여전히 전송을 기다렸다면 2000 이상이었을 것이다
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), anyString());
    }

    @Test
    void aFailingMailServerNoLongerFailsTheSignup() {
        doThrow(new IllegalStateException("smtp down"))
                .when(mailService).sendVerificationEmail(anyString(), anyString(), anyString());

        signup(newEmail());

        assertThat(userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email)).isPresent();
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), anyString());
    }

    @Test
    void theMailCarriesTheTokenThatWasStored() {
        signup(newEmail());

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), token.capture());
        User user = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElseThrow();
        assertThat(user.getEmailVerificationToken()).isEqualTo(token.getValue());
    }

    @Test
    void aRefusedSignupSendsNoSecondMail() {
        signup(newEmail());
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), anyString());

        assertThatThrownBy(() -> signup(email)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));

        verify(mailService, after(800).times(1)).sendVerificationEmail(eq(email), anyString(), anyString());
    }

    @Test
    void resendIssuesANewTokenOnceTheCooldownPasses() {
        signup(newEmail());
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), anyString());
        String firstToken = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElseThrow()
                .getEmailVerificationToken();

        authService.resendVerification(email); // 가입 직후라 너무 이르다: 조용히 무시한다
        verify(mailService, after(800).times(1)).sendVerificationEmail(eq(email), anyString(), anyString());

        // 첫 링크가 5분 전에 발급된 것으로 가장한다.
        User user = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElseThrow();
        user.issueEmailVerificationToken(firstToken, LocalDateTime.now().plusHours(24).minusMinutes(5));
        userRepository.save(user);

        authService.resendVerification(email);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000).times(2)).sendVerificationEmail(eq(email), anyString(), token.capture());
        assertThat(token.getAllValues().get(1)).isNotEqualTo(firstToken);
        assertThat(userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElseThrow().getEmailVerificationToken())
                .isEqualTo(token.getAllValues().get(1));
    }

    @Test
    void resendSaysNothingAboutUnknownOrAlreadyVerifiedAccounts() {
        authService.resendVerification("nobody-" + UUID.randomUUID() + "@example.com"); // 어느 쪽이든 오류 없음

        signup(newEmail());
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), anyString());
        User user = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElseThrow();
        user.verifyEmail();
        userRepository.save(user);

        authService.resendVerification(email);

        verify(mailService, after(800).times(1)).sendVerificationEmail(eq(email), anyString(), anyString());
    }
}
