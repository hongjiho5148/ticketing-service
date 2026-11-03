package com.ticketing.eventservice.config;

import com.ticketing.eventservice.auth.JwtAuthenticationFilter;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.common.ErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
                        // 서비스 사이 내부 호출(reservation-service의 hold/release/sell)은 게이트웨이/JWT를 거치지 않는다.
                        // 다른 서비스들의 /internal/**과 같은 신뢰 모델이다.
                        .requestMatchers("/internal/**").permitAll()
                        // 알림 구독은 사용자별이므로, 공개 GET /api/events/** 아래에 있어도 로그인이 필요하다.
                        // 관리자와 같은 이유로 그 매처보다 앞에 와야 한다.
                        .requestMatchers("/api/events/*/open-alert", "/api/events/*/waitlist").authenticated()
                        // 아래의 공개 GET 매처보다 위에 있어야 한다. 처음 맞는 것이 이기므로 그렇지 않으면
                        // GET /api/events/admin/stats가 "GET /api/events/**"에 걸린다.
                        .requestMatchers("/api/events/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.UNAUTHORIZED)));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(ErrorCode.FORBIDDEN.getStatus().value());
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.FORBIDDEN)));
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
