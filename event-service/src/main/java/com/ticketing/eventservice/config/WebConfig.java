package com.ticketing.eventservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * event-service has no Spring Security (its API is all public reads), so CORS can't hook into
 * HttpSecurity.cors(...) the way the other services do - it needs its own mapping instead.
 * Without this, the browser blocks every cross-origin response with no server-side error at all,
 * which is exactly why this was missed until someone actually opened the frontend against it.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public WebConfig(@Value("${cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
