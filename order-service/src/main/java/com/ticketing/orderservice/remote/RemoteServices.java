package com.ticketing.orderservice.remote;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link RemoteService} each client uses, from the {@code remote.*} settings. A service-to-service call
 * should finish in well under a second, and the slowest legitimate one (holding a seat behind the seat lock) is bounded
 * by the lock's own few seconds, so five seconds of read time only cuts off calls that are really stuck.
 *
 * <p>Every circuit breaker lives in one registry that is bound to the metrics registry, so each service exports
 * {@code resilience4j_circuitbreaker_*} (state, failure rate, calls refused while open) without further wiring.
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

    /** For an outside API whose normal answer time is different (e.g. a payment provider). */
    public RemoteService create(String name, String baseUrl, Duration readTimeout) {
        return new RemoteService(name, baseUrl, settings, readTimeout, circuitBreakers);
    }
}
