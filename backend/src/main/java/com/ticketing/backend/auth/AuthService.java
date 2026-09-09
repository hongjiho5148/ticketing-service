package com.ticketing.backend.auth;

import com.ticketing.backend.auth.dto.LoginRequest;
import com.ticketing.backend.auth.dto.LoginResponse;
import com.ticketing.backend.auth.dto.MeResponse;
import com.ticketing.backend.auth.dto.SignupRequest;
import com.ticketing.backend.auth.dto.SignupResponse;
import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.user.Role;
import com.ticketing.backend.user.User;
import com.ticketing.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.name(), Role.USER);
        return SignupResponse.from(userRepository.save(user));
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail());
        return new LoginResponse(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpirationSeconds());
    }

    public MeResponse getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return MeResponse.from(user);
    }
}
