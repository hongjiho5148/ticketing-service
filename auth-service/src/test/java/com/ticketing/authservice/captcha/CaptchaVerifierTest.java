package com.ticketing.authservice.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CaptchaVerifierTest {

    private static final String URL = "https://www.google.com/recaptcha/api/siteverify";

    private MockRestServiceServer google;
    private CaptchaVerifier verifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        google = MockRestServiceServer.bindTo(builder).build();
        verifier = new CaptchaVerifier(builder.build(), "test-secret", true);
    }

    private void expectError(String token, ErrorCode code) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void aTokenGoogleAcceptsPasses() {
        google.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of("secret", "test-secret", "response", "good-token")))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> verifier.verify("good-token")).doesNotThrowAnyException();
        google.verify();
    }

    @Test
    void aTokenGoogleRejectsFails() {
        google.expect(requestTo(URL))
                .andRespond(withSuccess("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}", MediaType.APPLICATION_JSON));

        expectError("used-token", ErrorCode.CAPTCHA_FAILED);
    }

    @Test
    void aMissingTokenIsRefusedWithoutCallingGoogle() {
        expectError(null, ErrorCode.CAPTCHA_REQUIRED);
        expectError("  ", ErrorCode.CAPTCHA_REQUIRED);
        google.verify(); // no request was expected, none was made
    }

    @Test
    void failsClosedWhenGoogleCannotBeReached() {
        google.expect(requestTo(URL)).andRespond(withServerError());

        expectError("any-token", ErrorCode.CAPTCHA_UNAVAILABLE);
    }

    @Test
    void withoutASecretOrWhenDisabledEverythingPasses() {
        assertThatCode(() -> new CaptchaVerifier(RestClient.create(), "", true).verify(null)).doesNotThrowAnyException();
        assertThatCode(() -> new CaptchaVerifier(RestClient.create(), "test-secret", false).verify(null)).doesNotThrowAnyException();
        assertThat(new CaptchaVerifier(RestClient.create(), "", true).isActive()).isFalse();
        assertThat(verifier.isActive()).isTrue();
    }
}
