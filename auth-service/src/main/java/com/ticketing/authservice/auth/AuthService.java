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

        // VerificationMailListener에 넘기면 이 트랜잭션이 커밋된 뒤에 보낸다. 가입이 더는
        // SMTP를 기다리지 않는다.
        eventPublisher.publishEvent(new VerificationMailRequested(user.getEmail(), user.getName(), token));

        return SignupResponse.from(user);
    }

    /**
     * 새 인증 링크를 보낸다. 주소가 인증 대기 중인 계정의 것이든 아니든 똑같이 응답해서(누가 가입했는지
     * 알아내는 데 쓸 수 없게) 같은 사람에게는 1분 안에 다시 메일을 보내지 않으므로, 누군가의 받은편지함을
     * 가득 채우는 데 쓸 수 없다.
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
        // 관리자 계정은 별도 관리자 앱의 엔드포인트(adminLogin)로만 로그인한다. 공개 사이트는
        // 관리자 토큰을 볼 일이 없다. 어떤 이메일이 관리자인지 드러나지 않도록 비밀번호가 틀렸을 때와 같은 오류를 낸다.
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
