package com.ticketing.eventservice.remote;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 다른 서비스 하나(또는 외부 API)에 대한 호출이다. 일반 {@code RestClient}에는 없는 보호 장치가 붙는다.
 *
 * <ul>
 *   <li><b>타임아웃.</b> 일반 클라이언트는 연결은 받아 주고 응답은 하지 않는 서비스를 영원히 기다린다. 그러면
 *       그 서비스를 호출하는 스레드가 모두 묶여 결국 서비스에 남는 스레드가 없어진다. 연결과 읽기에 상한을 둔다.
 *   <li><b>서킷 브레이커.</b> 실패(I/O 오류, 타임아웃, 5xx)가 이어지면 한동안 호출이 곧바로 실패한다. 호출마다
 *       자기 타임아웃을 기다리지 않으므로 호출하는 쪽의 스레드가 비고, 힘들어하는 서비스가 회복할 여유가 생긴다.
 *       4xx 응답은 비즈니스 결과(없음, 충돌, 사라짐)이므로 세지 않는다.
 *   <li><b>멱등한 호출은 한 번 재시도.</b> 빠르게 돌아오는 실패(연결 거부, 502/503)에만 하고,
 *       타임아웃에는 하지 않으며(대기만 두 배가 된다) 반복해도 안전하지 않은 호출에도 하지 않는다.
 * </ul>
 *
 * 서킷이 열려 있으면 호출은 {@link ResourceAccessException}으로 실패한다. 연결이 거부됐을 때와 같은 타입이라
 * {@code RestClientException}을 "서비스 이용 불가"로 바꾸고 있던 호출자는 전과 똑같이 동작한다.
 */
public class RemoteService {

    private static final Logger log = LoggerFactory.getLogger(RemoteService.class);

    private final String name;
    private final RestClient restClient;
    private final CircuitBreaker breaker;
    private final long retryBackoffMs;

    public RemoteService(
            String name, String baseUrl, RemoteSettings settings, Duration readTimeout, CircuitBreakerRegistry registry) {
        this.name = name;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        // 레지스트리로 만들어서 상태와 호출 수가 메트릭으로 내보내진다(RemoteServices 참고)
        this.breaker = registry.circuitBreaker(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openWait())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                // I/O 오류, 타임아웃, 5xx는 서비스가 실패한 것이고, 나머지(4xx)는 답이다
                .recordException(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build());
        this.retryBackoffMs = settings.retryBackoff().toMillis();
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "Circuit '{}' went from {} to {}", name, event.getStateTransition().getFromState(),
                event.getStateTransition().getToState()));
    }

    public RestClient rest() {
        return restClient;
    }

    public CircuitBreaker circuitBreaker() {
        return breaker;
    }

    /** 스스로 반복하면 안 되는 호출용이다(두 번 보내면 안전하지 않다). */
    public <T> T call(Supplier<T> request) {
        return once(request);
    }

    /** 두 번 보내도 안전한 호출용이다(읽기, 또는 설계상 멱등한 작업). */
    public <T> T callIdempotent(Supplier<T> request) {
        try {
            return once(request);
        } catch (RestClientException e) {
            if (!failedQuickly(e)) {
                throw e;
            }
            sleepBriefly();
            return once(request);
        }
    }

    private <T> T once(Supplier<T> request) {
        try {
            return breaker.executeSupplier(request);
        } catch (CallNotPermittedException e) {
            throw new CircuitOpenException(name);
        }
    }

    /** 연결 거부/리셋이나 502/503이다. 한 번 더 해 볼 만한 일시적인 문제이고, 타임아웃이나 열린 서킷은 아니다. */
    private static boolean failedQuickly(RestClientException e) {
        if (e instanceof CircuitOpenException) {
            return false;
        }
        if (e instanceof HttpServerErrorException server) {
            HttpStatus status = HttpStatus.resolve(server.getStatusCode().value());
            return status == HttpStatus.BAD_GATEWAY || status == HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (e instanceof ResourceAccessException) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(retryBackoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 서킷이 열린 서비스를 호출하는 대신 던진다. */
    public static final class CircuitOpenException extends ResourceAccessException {
        CircuitOpenException(String name) {
            super("Circuit '" + name + "' is open - the call was not made", new IOException("circuit open"));
        }
    }
}
