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
 * 가장자리에서 클라이언트별로 요청을 제한한다. 요청은 자기 메서드와 경로에 맞는 첫 규칙(로그인, 대기열 입장,
 * 좌석 홀드 등)을 통과해야 하고, 그 규칙이 빠지겠다고 하지 않았다면 전체 제한도 통과해야 한다. 클라이언트는 IP로
 * 구분한다. nginx가 X-Real-IP를 자기가 실제로 본 주소로 덮어쓰므로 여기서 믿을 수 있다(게이트웨이는
 * nginx를 거치지 않고는 닿지 않는다).
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
        // 프리플라이트는 CORS 필터가 답하고 클라이언트에게 비용이 들지 않는다. 제한하는 것은 /api 트래픽뿐이다.
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
        // 모든 서비스의 ErrorResponse와 같은 형태라서, 프론트엔드가 다른 오류처럼 메시지를 보여 준다.
        response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"요청이 너무 많아요. "
                + decision.retryAfterSeconds() + "초 후에 다시 시도해주세요.\",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }
}
