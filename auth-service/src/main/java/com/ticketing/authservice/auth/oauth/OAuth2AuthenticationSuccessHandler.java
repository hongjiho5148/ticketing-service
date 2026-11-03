package com.ticketing.authservice.auth.oauth;

import com.ticketing.authservice.auth.JwtTokenProvider;
import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.user.Role;
import com.ticketing.authservice.user.User;
import com.ticketing.authservice.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final String frontendUrl;

    public OAuth2AuthenticationSuccessHandler(
            JwtTokenProvider jwtTokenProvider, UserRepository userRepository, @Value("${app.frontend-url}") String frontendUrl) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Long userId = Long.valueOf(String.valueOf(oAuth2User.getAttributes().get("internalUserId")));
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        // 소셜 로그인은 관리자 계정의 토큰을 발급하지 않는다. 관리자는 관리자 앱의 자체 로그인을 쓴다.
        if (user.getRole() == Role.ADMIN) {
            response.sendRedirect(frontendUrl + "/login?error=oauth2");
            return;
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail(), user.getRole());

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + "/oauth2/redirect")
                .queryParam("accessToken", accessToken)
                .queryParam("refreshToken", refreshToken)
                .queryParam("expiresIn", jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build()
                .toUriString();

        response.sendRedirect(redirectUrl);
    }
}
