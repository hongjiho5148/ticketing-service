package com.ticketing.authservice.config;

import com.ticketing.authservice.auth.JwtAuthenticationFilter;
import com.ticketing.authservice.auth.oauth.CustomOAuth2UserService;
import com.ticketing.authservice.auth.oauth.OAuth2AuthenticationFailureHandler;
import com.ticketing.authservice.auth.oauth.OAuth2AuthenticationSuccessHandler;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.common.ErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper,
            CustomOAuth2UserService customOAuth2UserService,
            OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // CORS는 게이트웨이 가장자리(그쪽 CorsFilter)에서 한 번만 처리한다. 여기서 또 처리하면
                // 응답에 Access-Control-Allow-Origin이 두 번 붙고 브라우저는 이를 잘못된 응답으로 거절하므로,
                // 이 서비스는 일부러 CORS를 전혀 다루지 않는다.
                // OAuth2 로그인의 인가 요청 왕복에는 제공자로 리다이렉트하고 돌아오는 사이에
                // state/PKCE 파라미터를 들고 있을 세션이 필요하다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/error").permitAll()
                        // 메트릭은 docker 네트워크 안에만 있는 별도 관리 포트(9090)로 내보낸다. 이 보안 체인이 그 포트에도
                        // 적용되는데, Prometheus는 토큰을 들고 오지 않는다.
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/admin/login", "/api/auth/verify-email", "/api/auth/resend-verification").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.UNAUTHORIZED)));
                }))
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
