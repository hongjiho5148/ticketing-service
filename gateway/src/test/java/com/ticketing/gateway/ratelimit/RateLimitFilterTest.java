package com.ticketing.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.getGlobal().setCapacity(8);
        properties.getGlobal().setPerMinute(8);
        properties.setRules(List.of(
                rule("login", "POST", "/api/auth/login", 2, false),
                rule("transfer", "POST", "/api/orders/*/transfer", 1, false),
                rule("queue-status", "GET", "/api/queue/status", 100, true)));
        filter = new RateLimitFilter(properties, new RateLimiter());
    }

    private static RateLimitProperties.Rule rule(String name, String method, String path, int capacity, boolean skipGlobal) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setName(name);
        rule.setMethods(List.of(method));
        rule.setPaths(List.of(path));
        rule.setCapacity(capacity);
        rule.setPerMinute(capacity); // 테스트 동안 아무것도 다시 채워지지 않을 만큼 느리다
        rule.setSkipGlobal(skipGlobal);
        return rule;
    }

    private MockHttpServletResponse call(String method, String path, String ip) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.addHeader("X-Real-IP", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void ruleLimitAppliesPerClientAndAnswers429WithTheErrorShape() throws Exception {
        assertThat(call("POST", "/api/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = call("POST", "/api/auth/login", "1.1.1.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(limited.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"code\":\"TOO_MANY_REQUESTS\"")
                .contains("다시 시도해주세요");

        assertThat(call("POST", "/api/auth/login", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void pathPatternsAndMethodsDecideWhichRuleApplies() throws Exception {
        assertThat(call("POST", "/api/orders/7/transfer", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/orders/8/transfer", "1.1.1.1").getStatus()).isEqualTo(429);

        // 같은 경로지만 메서드가 다르다. 양도 규칙이 아니다.
        assertThat(call("GET", "/api/orders/9/transfer", "1.1.1.1").getStatus()).isEqualTo(200);
        // 로그인 시도는 양도 버킷에 올라가지 않는다.
        assertThat(call("POST", "/api/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void globalLimitCoversEverythingElse() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(call("GET", "/api/events", "3.3.3.3").getStatus()).isEqualTo(200);
        }
        assertThat(call("GET", "/api/events", "3.3.3.3").getStatus()).isEqualTo(429);
        assertThat(call("GET", "/api/wishlist", "3.3.3.3").getStatus()).isEqualTo(429);
    }

    @Test
    void ruleTrafficAlsoCountsAgainstTheGlobalBucketUnlessItOptsOut() throws Exception {
        // 대기열 폴링은 빠지므로, 아무리 두드려도 전체 허용량을 쓰지 않는다...
        for (int i = 0; i < 50; i++) {
            assertThat(call("GET", "/api/queue/status", "4.4.4.4").getStatus()).isEqualTo(200);
        }
        assertThat(call("GET", "/api/events", "4.4.4.4").getStatus()).isEqualTo(200);

        // ...반면 skip-global이 없는 규칙은 전체에도 올라간다. 로그인 2번 + 6번 더 = 전체 허용 8번.
        call("POST", "/api/auth/login", "5.5.5.5");
        call("POST", "/api/auth/login", "5.5.5.5");
        for (int i = 0; i < 6; i++) {
            assertThat(call("GET", "/api/events", "5.5.5.5").getStatus()).isEqualTo(200);
        }
        assertThat(call("GET", "/api/events", "5.5.5.5").getStatus()).isEqualTo(429);
    }

    @Test
    void preflightNonApiTrafficAndADisabledLimiterAreLeftAlone() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("OPTIONS", "/api/auth/login", "6.6.6.6").getStatus()).isEqualTo(200);
            assertThat(call("GET", "/oauth2/authorization/google", "6.6.6.6").getStatus()).isEqualTo(200);
        }

        properties.setEnabled(false);
        for (int i = 0; i < 20; i++) {
            assertThat(call("POST", "/api/auth/login", "7.7.7.7").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void fallsBackToTheSocketAddressWithoutAForwardedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/1/transfer");
        request.setRequestURI("/api/orders/1/transfer");
        request.setRemoteAddr("9.9.9.9");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/orders/2/transfer");
        second.setRequestURI("/api/orders/2/transfer");
        second.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
    }
}
