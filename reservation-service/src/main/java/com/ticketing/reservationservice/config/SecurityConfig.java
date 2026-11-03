package com.ticketing.reservationservice.config;

import com.ticketing.reservationservice.auth.JwtAuthenticationFilter;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.common.ErrorResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // CORS는 게이트웨이 가장자리(그쪽 CorsFilter)에서 한 번만 처리한다. 여기서 또 처리하면
                // 응답에 Access-Control-Allow-Origin이 두 번 붙고 브라우저는 이를 잘못된 응답으로 거절하므로,
                // 이 서비스는 일부러 CORS를 전혀 다루지 않는다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/error").permitAll()
                        // 메트릭은 docker 네트워크 안에만 있는 별도 관리 포트(9090)로 내보낸다. 이 보안 체인이 그 포트에도
                        // 적용되는데, Prometheus는 토큰을 들고 오지 않는다.
                        .requestMatchers("/actuator/**").permitAll()
                        // 서비스 사이 호출 전용이며 docker 네트워크 안에서 직접 닿는다. 사용자 JWT를 싣지 않으므로
                        // 일반 인증 검사를 거칠 수 없다.
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.UNAUTHORIZED)));
                }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
