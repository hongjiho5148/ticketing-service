package com.ticketing.authservice.auth;

import com.ticketing.authservice.auth.dto.LoginRequest;
import com.ticketing.authservice.auth.dto.LoginResponse;
import com.ticketing.authservice.auth.dto.MeResponse;
import com.ticketing.authservice.auth.dto.SignupRequest;
import com.ticketing.authservice.auth.dto.SignupResponse;
import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.user.AuthProvider;
import com.ticketing.authservice.user.User;
import com.ticketing.authservice.user.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final MailService mailService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            MailService mailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.mailService = mailService;
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

        mailService.sendVerificationEmail(user.getEmail(), user.getName(), token);

        return SignupResponse.from(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByProviderAndEmail(AuthProvider.LOCAL, request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail());
        return new LoginResponse(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpirationSeconds());
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
}
