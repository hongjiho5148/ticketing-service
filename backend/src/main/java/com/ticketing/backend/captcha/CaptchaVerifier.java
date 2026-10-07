package com.ticketing.backend.captcha;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Checks a reCAPTCHA token with Google's siteverify API. The token comes from the widget in the
 * browser and is single-use, so it is verified server-side here - a client can't vouch for itself.
 * With no secret key (or captcha.enabled=false) every check passes, which keeps local development
 * and load tests working without keys. It fails closed otherwise: if Google can't be reached,
 * the request is refused rather than waved through, since "captcha is down" is exactly what a bot hopes for.
 */
@Component
public class CaptchaVerifier {

    private final RestClient restClient;
    private final String secret;
    private final boolean enabled;

    @Autowired
    public CaptchaVerifier(
            @Value("${captcha.secret:}") String secret,
            @Value("${captcha.enabled:true}") boolean enabled,
            @Value("${captcha.verify-url}") String verifyUrl) {
        this(buildClient(verifyUrl), secret, enabled);
    }

    CaptchaVerifier(RestClient restClient, String secret, boolean enabled) {
        this.restClient = restClient;
        this.secret = secret;
        this.enabled = enabled;
    }

    private static RestClient buildClient(String verifyUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        return RestClient.builder().baseUrl(verifyUrl).requestFactory(factory).build();
    }

    public boolean isActive() {
        return enabled && secret != null && !secret.isBlank();
    }

    /** Returns normally for a valid token; throws ApiException (CAPTCHA_REQUIRED / CAPTCHA_FAILED / CAPTCHA_UNAVAILABLE) otherwise. */
    public void verify(String token) {
        if (!isActive()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.CAPTCHA_REQUIRED);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);

        Map<?, ?> result;
        try {
            result = restClient.post().contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.CAPTCHA_UNAVAILABLE);
        }
        if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
            throw new ApiException(ErrorCode.CAPTCHA_FAILED);
        }
    }
}
