package com.ticketing.backend.remote;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 각 클라이언트가 쓰는 {@link RemoteService}를 {@code remote.*} 설정으로 만든다. 서비스 사이 호출은 1초보다
 * 한참 짧게 끝나야 하고, 가장 느린 정상 호출(좌석 잠금 뒤에서 좌석을 잡는 일)도 잠금 자체의 몇 초로 상한이
 * 있으므로, 읽기 시간 5초는 정말로 걸려 버린 호출만 끊는다.
 *
 * <p>모든 서킷 브레이커는 메트릭 레지스트리에 연결된 하나의 레지스트리에 있어서, 따로 연결하지 않아도 각
 * 서비스가 {@code resilience4j_circuitbreaker_*}(상태, 실패율, 열려 있는 동안 거절한 호출)를 내보낸다.
 */
@Component
public class RemoteServices {

    private final RemoteSettings settings;
    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();

    public RemoteServices(
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${remote.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${remote.read-timeout-ms:5000}") long readTimeoutMs,
            @Value("${remote.circuit.window-size:30}") int windowSize,
            @Value("${remote.circuit.minimum-calls:10}") int minimumCalls,
            @Value("${remote.circuit.failure-rate-percent:50}") float failureRatePercent,
            @Value("${remote.circuit.open-wait-ms:10000}") long openWaitMs,
            @Value("${remote.circuit.half-open-calls:3}") int halfOpenCalls,
            @Value("${remote.retry-backoff-ms:150}") long retryBackoffMs) {
        this.settings = new RemoteSettings(
                Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(readTimeoutMs),
                windowSize,
                minimumCalls,
                failureRatePercent,
                Duration.ofMillis(openWaitMs),
                halfOpenCalls,
                Duration.ofMillis(retryBackoffMs));
        MeterRegistry metrics = meterRegistry.getIfAvailable();
        if (metrics != null) {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(metrics);
        }
    }

    public RemoteService create(String name, String baseUrl) {
        return new RemoteService(name, baseUrl, settings, settings.readTimeout(), circuitBreakers);
    }

    /** 정상 응답 시간이 다른 외부 API(예: 결제 대행사)용이다. */
    public RemoteService create(String name, String baseUrl, Duration readTimeout) {
        return new RemoteService(name, baseUrl, settings, readTimeout, circuitBreakers);
    }
}
