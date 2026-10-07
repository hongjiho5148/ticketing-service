package com.ticketing.authservice.auth;

import com.ticketing.authservice.auth.dto.AdminLoginResponse;
import com.ticketing.authservice.auth.dto.ChangePasswordRequest;
import com.ticketing.authservice.auth.dto.LoginRequest;
import com.ticketing.authservice.auth.dto.LoginResponse;
import com.ticketing.authservice.auth.dto.MeResponse;
import com.ticketing.authservice.auth.dto.SignupRequest;
import com.ticketing.authservice.auth.dto.SignupResponse;
import com.ticketing.authservice.auth.dto.UpdateProfileRequest;
import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.user.AuthProvider;
import com.ticketing.authservice.user.Role;
import com.ticketing.authservice.user.User;
import com.ticketing.authservice.user.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ApplicationEventPublisher eventPublisher;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByProviderAndEmail(AuthProvider.LOCAL, request.email())) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = User.localSignup(request.email(), passwordEncoder.encode(request.password()), request.name());
        String token = UUID.randomUUID().toString();
        user.issueEmailVerificationToken(token, LocalDateTime.now().plus(VERIFICATION_TOKEN_TTL));
        user = userRepository.save(user);

        // Handed to VerificationMailListener, which sends it after this transaction commits - signup
        // no longer waits on SMTP.
        eventPublisher.publishEvent(new VerificationMailRequested(user.getEmail(), user.getName(), token));

        return SignupResponse.from(user);
    }

    /**
     * Sends a fresh verification link. Answers the same whether or not the address belongs to a
     * pending account (so it can't be used to find out who is registered), and refuses to mail the
     * same person again within a minute so it can't be used to flood someone's inbox.
     */
    @Transactional
    public void resendVerification(String email) {
        userRepository.findByProviderAndEmail(AuthProvider.LOCAL, email)
                .filter(user -> !user.isEmailVerified())
                .filter(user -> !issuedWithinCooldown(user))
                .ifPresent(user -> {
                    String token = UUID.randomUUID().toString();
                    user.issueEmailVerificationToken(token, LocalDateTime.now().plus(VERIFICATION_TOKEN_TTL));
                    eventPublisher.publishEvent(new VerificationMailRequested(user.getEmail(), user.getName(), token));
                });
    }

    private boolean issuedWithinCooldown(User user) {
        LocalDateTime expiresAt = user.getEmailVerificationExpiresAt();
        if (expiresAt == null) {
            return false;
        }
        LocalDateTime issuedAt = expiresAt.minus(VERIFICATION_TOKEN_TTL);
        return issuedAt.isAfter(LocalDateTime.now().minus(RESEND_COOLDOWN));
    }

    public LoginResponse login(LoginRequest request) {
        User user = authenticate(request);
        // Admin accounts only sign in through the separate admin app's endpoint (adminLogin) - the
        // public site never sees an admin token. Same error as a wrong password so this doesn't
        // reveal which emails are admins.
        if (user.getRole() == Role.ADMIN) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail(), user.getRole());
        return new LoginResponse(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpirationSeconds());
    }

    public AdminLoginResponse adminLogin(LoginRequest request) {
        User user = authenticate(request);
        if (user.getRole() != Role.ADMIN) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        String accessToken = jwtTokenProvider.createAdminAccessToken(user.getId(), user.getEmail(), user.getRole());
        return new AdminLoginResponse(accessToken, jwtTokenProvider.getAdminAccessTokenExpirationSeconds());
    }

    private User authenticate(LoginRequest request) {
        User user = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        return user;
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_VERIFICATION_TOKEN));
        if (user.getEmailVerificationExpiresAt() == null || user.getEmailVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.VERIFICATION_TOKEN_EXPIRED);
        }
        user.verifyEmail();
    }

    public MeResponse getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return MeResponse.from(user);
    }

    @Transactional
    public MeResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        user.updateName(request.name());
        return MeResponse.from(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new ApiException(ErrorCode.OAUTH_ACCOUNT_NO_PASSWORD);
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }
}
