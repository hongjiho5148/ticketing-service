package com.ticketing.authservice.captcha;

import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
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
 * Google의 siteverify API로 reCAPTCHA 토큰을 검사한다. 토큰은 브라우저의 위젯에서 오고 한 번만 쓸 수 있으므로
 * 여기 서버에서 검증한다. 클라이언트는 스스로를 보증할 수 없다. 시크릿 키가 없거나(또는 captcha.enabled=false)
 * 이면 모든 검사가 통과하므로 키 없이도 로컬 개발과 부하 테스트가 된다. 그 밖에는 닫는다(fail closed). Google에
 * 닿지 못하면 요청을 그냥 통과시키지 않고 거절한다. "캡차가 죽었다"는 것은 봇이 바라는 바로 그 상황이기 때문이다.
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

    /** 유효한 토큰이면 정상적으로 돌아오고, 아니면 ApiException(CAPTCHA_REQUIRED / CAPTCHA_FAILED / CAPTCHA_UNAVAILABLE)을 던진다. */
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
