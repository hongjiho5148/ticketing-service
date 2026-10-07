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
 * Verification mail goes out after the signup transaction commits, on another thread. Because it
 * hangs off a real commit this test is deliberately not @Transactional: it creates real users in the
 * compose MySQL and deletes them again. Run inside the compose network with DB_HOST=mysql.
 */
// Dummy OAuth ids: the real ones come from .env, and Spring refuses to build the OAuth2 client config without some.
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
        // The first signup in a fresh JVM pays for class loading, the connection pool and BCrypt warm-up
        // (seconds in a container) - that is not what is being measured, so get it out of the way first.
        String warmUp = "async-mail-warmup-" + UUID.randomUUID() + "@example.com";
        signup(warmUp);
        userRepository.findByProviderAndEmail(AuthProvider.LOCAL, warmUp).ifPresent(userRepository::delete);

        doAnswer(inv -> {
            Thread.sleep(2000); // a slow SMTP server
            return null;
        }).when(mailService).sendVerificationEmail(anyString(), anyString(), anyString());

        long start = System.nanoTime();
        signup(newEmail());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1500); // would be 2000+ if signup still waited for the send
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

        authService.resendVerification(email); // too soon after signup: quietly ignored
        verify(mailService, after(800).times(1)).sendVerificationEmail(eq(email), anyString(), anyString());

        // Pretend the first link was issued five minutes ago.
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
        authService.resendVerification("nobody-" + UUID.randomUUID() + "@example.com"); // no error either way

        signup(newEmail());
        verify(mailService, timeout(5000)).sendVerificationEmail(eq(email), anyString(), anyString());
        User user = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElseThrow();
        user.verifyEmail();
        userRepository.save(user);

        authService.resendVerification(email);

        verify(mailService, after(800).times(1)).sendVerificationEmail(eq(email), anyString(), anyString());
    }
}
