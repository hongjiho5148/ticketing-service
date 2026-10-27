package com.ticketing.backend.remote;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link RemoteService} each client uses, from the {@code remote.*} settings. A service-to-service call
 * should finish in well under a second, and the slowest legitimate one (holding a seat behind the seat lock) is bounded
 * by the lock's own few seconds, so five seconds of read time only cuts off calls that are really stuck.
 */
@Component
public class RemoteServices {

    private final RemoteSettings settings;

    public RemoteServices(
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
    }

    public RemoteService create(String name, String baseUrl) {
        return new RemoteService(name, baseUrl, settings, settings.readTimeout());
    }

    /** For an outside API whose normal answer time is different (e.g. a payment provider). */
    public RemoteService create(String name, String baseUrl, Duration readTimeout) {
        return new RemoteService(name, baseUrl, settings, readTimeout);
    }
}
