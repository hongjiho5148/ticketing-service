package com.ticketing.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 게이트웨이의 라우트는 @RequestMapping 컨트롤러가 아니라 RouterFunction이라서 일반적인
 * WebMvcConfigurer.addCorsMappings는 그것들을 보지 못한다. Spring의 DispatcherServlet이 모든 OPTIONS
 * 프리플라이트에 먼저 직접 답하기 때문이다(Allow 헤더가 있는 맨 200이고 CORS 헤더는 전혀 없다).
 * 요청이 프록시 필터에 닿기도 전이다. CorsFilter는 라우팅보다 앞서 서블릿 Filter로 동작하므로, 하위에 라우트가
 * 어떻게 등록됐든 프리플라이트를 가로채고 실제 응답에도 올바르게 표시한다.
 */
@Configuration
public class CorsConfig {

    /** 레이트 리미터(순서 -40)보다 앞에 있으므로 제한된 429 응답에도 CORS 헤더가 실린다. */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(@Value("${cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(-50);
        return registration;
    }
}
