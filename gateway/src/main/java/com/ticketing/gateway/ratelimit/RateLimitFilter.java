package com.ticketing.gateway.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client request throttling at the edge. A request must pass the first rule that matches its
 * method and path (login, queue entry, seat hold, ...) and, unless that rule opts out, the global
 * limit too. Clients are told apart by IP: nginx overwrites X-Real-IP with the address it really
 * saw, so it can be trusted here (the gateway is never reachable except through nginx).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final RateLimiter limiter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitProperties properties, RateLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Preflight is answered by the CORS filter and costs the client nothing - and only /api traffic is throttled.
        return !properties.isEnabled()
                || "OPTIONS".equals(request.getMethod())
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientIp(request);
        RateLimitProperties.Rule rule = matchingRule(request);

        if (rule != null) {
            RateLimiter.Decision decision = limiter.check(rule.getName() + "|" + client, rule.getCapacity(), rule.getPerMinute());
            if (!decision.allowed()) {
                reject(response, rule.getName(), client, decision);
                return;
            }
        }
        if (rule == null || !rule.isSkipGlobal()) {
            RateLimitProperties.Limit global = properties.getGlobal();
            RateLimiter.Decision decision = limiter.check("global|" + client, global.getCapacity(), global.getPerMinute());
            if (!decision.allowed()) {
                reject(response, "global", client, decision);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private RateLimitProperties.Rule matchingRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            boolean methodMatches = rule.getMethods().isEmpty()
                    || rule.getMethods().stream().anyMatch(m -> m.equalsIgnoreCase(method));
            if (methodMatches && rule.getPaths().stream().anyMatch(p -> pathMatcher.match(p, path))) {
                return rule;
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, String ruleName, String client, RateLimiter.Decision decision)
            throws IOException {
        log.debug("Rate limited {} on rule {}", client, ruleName);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Same shape as every service's ErrorResponse, so the frontend shows the message like any other error.
        response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"요청이 너무 많아요. "
                + decision.retryAfterSeconds() + "초 후에 다시 시도해주세요.\",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }
}
