package com.ticketing.gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /** CORS 필터(CorsConfig 참고) 바로 뒤에 있으므로, 429도 브라우저가 읽는 데 필요한 CORS 헤더를 가진다. */
    public static final int ORDER = -40;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, new RateLimiter()));
        registration.setOrder(ORDER);
        return registration;
    }
}
