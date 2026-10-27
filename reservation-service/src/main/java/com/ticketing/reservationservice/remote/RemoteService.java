package com.ticketing.reservationservice.remote;

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
 * A call to one other service (or an outside API), with the protections a plain {@code RestClient} does not have:
 *
 * <ul>
 *   <li><b>Timeouts.</b> A plain client waits forever for a service that accepts the connection and never answers, so
 *       every thread that calls it is stuck until the whole service has no threads left. Connect and read are bounded.
 *   <li><b>Circuit breaker.</b> After a run of failures (I/O errors, timeouts, 5xx) the calls fail at once for a while
 *       instead of each one waiting out its own timeout, which keeps the callers' threads free and gives the struggling
 *       service room to recover. 4xx answers are business results (not found, conflict, gone) and never count.
 *   <li><b>One retry for idempotent calls</b>, only for failures that come back quickly (connection refused, 502/503),
 *       never for a timeout (that would just double the wait) and never for a call that is not safe to repeat.
 * </ul>
 *
 * When the circuit is open the call fails with a {@link ResourceAccessException} - the same type a refused connection
 * gives - so callers that already turn {@code RestClientException} into "service unavailable" behave as before.
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
        // created through the registry so its state and call counts are exported as metrics (see RemoteServices)
        this.breaker = registry.circuitBreaker(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openWait())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                // I/O errors, timeouts and 5xx are the service failing; anything else (4xx) is an answer
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

    /** For a call that must not be repeated on its own (it is not safe to send twice). */
    public <T> T call(Supplier<T> request) {
        return once(request);
    }

    /** For a call that is safe to send twice (a read, or an operation that is idempotent by design). */
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

    /** Connection refused / reset or a 502/503 - a blip worth one more try. Not a timeout, not an open circuit. */
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

    /** Thrown instead of calling a service whose circuit is open. */
    public static final class CircuitOpenException extends ResourceAccessException {
        CircuitOpenException(String name) {
            super("Circuit '" + name + "' is open - the call was not made", new IOException("circuit open"));
        }
    }
}
