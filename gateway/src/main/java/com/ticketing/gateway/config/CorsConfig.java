package com.ticketing.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Gateway's routes are RouterFunctions, not @RequestMapping controllers, so a normal
 * WebMvcConfigurer.addCorsMappings never sees them - Spring's DispatcherServlet answers every
 * OPTIONS preflight itself first (a bare 200 with an Allow header, no CORS headers at all),
 * before a request would ever reach the proxy filter. A CorsFilter runs as a servlet Filter ahead
 * of routing entirely, so it intercepts preflight and tags real responses correctly regardless of
 * how a route is registered downstream.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(@Value("${cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
