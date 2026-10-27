package com.ticketing.eventservice.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * How a call to another service behaves when that service is slow, down or failing, against a real HTTP server on a
 * local port (no Spring context, no containers). Timeouts are short so the whole class runs in a few seconds.
 */
class RemoteServiceTest {

    private static final RemoteSettings SETTINGS = new RemoteSettings(
            Duration.ofMillis(300), Duration.ofMillis(300), 10, 4, 50f, Duration.ofMillis(400), 2, Duration.ofMillis(10));

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger okHits = new AtomicInteger();
    private final AtomicInteger hangHits = new AtomicInteger();
    private final AtomicInteger errHits = new AtomicInteger();
    private final AtomicInteger blipHits = new AtomicInteger();
    private final AtomicInteger flakyHits = new AtomicInteger();
    private final AtomicBoolean flakyHealthy = new AtomicBoolean(false);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/ok", ex -> {
            okHits.incrementAndGet();
            reply(ex, 200, "ok");
        });
        server.createContext("/hang", ex -> {
            hangHits.incrementAndGet();
            try {
                Thread.sleep(4000); // accepts the request and never answers in time
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "too late");
        });
        server.createContext("/err", ex -> {
            errHits.incrementAndGet();
            reply(ex, 500, "boom");
        });
        server.createContext("/missing", ex -> reply(ex, 404, "no such thing"));
        server.createContext("/blip", ex -> reply(ex, blipHits.incrementAndGet() == 1 ? 503 : 200, "blip"));
        server.createContext("/flaky", ex -> {
            flakyHits.incrementAndGet();
            reply(ex, flakyHealthy.get() ? 200 : 500, "flaky");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private RemoteService remote() {
        return new RemoteService("test-service", baseUrl, SETTINGS, SETTINGS.readTimeout(), CircuitBreakerRegistry.ofDefaults());
    }

    private static String get(RemoteService remote, String path) {
        return remote.rest().get().uri(path).retrieve().body(String.class);
    }

    @Test
    void aSuccessfulCallReturnsTheBody() {
        RemoteService remote = remote();

        assertThat(remote.call(() -> get(remote, "/ok"))).isEqualTo("ok");
    }

    @Test
    void aServiceThatNeverAnswersFailsAtTheReadTimeoutInsteadOfHangingForever() {
        RemoteService remote = remote();

        long started = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> remote.call(() -> get(remote, "/hang"))).isInstanceOf(ResourceAccessException.class));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isBetween(250L, 2000L);
    }

    @Test
    void aRefusedConnectionFailsQuickly() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        RemoteService remote = new RemoteService(
                "nobody-home", "http://127.0.0.1:" + closedPort, SETTINGS, SETTINGS.readTimeout(), CircuitBreakerRegistry.ofDefaults());

        long started = System.nanoTime();
        assertThatThrownBy(() -> remote.call(() -> get(remote, "/ok"))).isInstanceOf(ResourceAccessException.class);

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1500L);
    }

    @Test
    void afterEnoughFailuresTheCircuitOpensAndCallsFailWithoutReachingTheService() {
        RemoteService remote = remote();
        for (int i = 0; i < SETTINGS.minimumCalls(); i++) { // just enough failures for the breaker to judge
            assertThatThrownBy(() -> remote.call(() -> get(remote, "/err"))).isInstanceOf(HttpServerErrorException.class);
        }
        int hitsWhenItOpened = errHits.get();
        assertThat(remote.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> remote.call(() -> get(remote, "/err")))
                    .isInstanceOf(RemoteService.CircuitOpenException.class)
                    .isInstanceOf(ResourceAccessException.class); // what callers already treat as "service unavailable"
        }

        assertThat(errHits.get()).isEqualTo(hitsWhenItOpened); // none of the ten reached the service
    }

    @Test
    void whileTheCircuitIsOpenEvenACallToAHealthyPathIsRefusedAndNeverSent() {
        RemoteService remote = remote();
        for (int i = 0; i < SETTINGS.minimumCalls(); i++) { // just enough failures for the breaker to judge
            assertThatThrownBy(() -> remote.call(() -> get(remote, "/err"))).isInstanceOf(HttpServerErrorException.class);
        }

        assertThatThrownBy(() -> remote.call(() -> get(remote, "/ok"))).isInstanceOf(RemoteService.CircuitOpenException.class);

        assertThat(okHits.get()).isZero();
    }

    @Test
    void theCircuitClosesAgainOnceTheServiceRecovers() throws InterruptedException {
        RemoteService remote = remote();
        for (int i = 0; i < SETTINGS.minimumCalls(); i++) { // just enough failures for the breaker to judge
            assertThatThrownBy(() -> remote.call(() -> get(remote, "/flaky"))).isInstanceOf(HttpServerErrorException.class);
        }
        assertThat(remote.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        flakyHealthy.set(true);
        Thread.sleep(SETTINGS.openWait().toMillis() + 150); // the wait is over: trial calls are let through
        assertThat(remote.call(() -> get(remote, "/flaky"))).isEqualTo("flaky");
        assertThat(remote.call(() -> get(remote, "/flaky"))).isEqualTo("flaky");

        assertThat(remote.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(remote.call(() -> get(remote, "/flaky"))).isEqualTo("flaky");
    }

    @Test
    void notFoundIsAnAnswerNotAFailureAndNeverOpensTheCircuit() {
        RemoteService remote = remote();

        for (int i = 0; i < 25; i++) {
            assertThatThrownBy(() -> remote.call(() -> get(remote, "/missing"))).isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        assertThat(remote.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anIdempotentCallIsTriedOnceMoreAfterA503ButOtherCallsAreNot() {
        RemoteService remote = remote();
        assertThat(remote.callIdempotent(() -> get(remote, "/blip"))).isEqualTo("blip");
        assertThat(blipHits.get()).isEqualTo(2);

        blipHits.set(0); // the next call to /blip answers 503 again
        assertThatThrownBy(() -> remote.call(() -> get(remote, "/blip"))).isInstanceOf(HttpServerErrorException.class);
        assertThat(blipHits.get()).isEqualTo(1);
    }

    @Test
    void aTimeoutIsNeverRetriedBecauseThatWouldOnlyDoubleTheWait() {
        RemoteService remote = remote();

        assertThatThrownBy(() -> remote.callIdempotent(() -> get(remote, "/hang"))).isInstanceOf(ResourceAccessException.class);

        assertThat(hangHits.get()).isEqualTo(1);
    }

    @Test
    void anIdempotentCallToAnOpenCircuitIsNotRetried() {
        RemoteService remote = remote();
        for (int i = 0; i < SETTINGS.minimumCalls(); i++) { // just enough failures for the breaker to judge
            assertThatThrownBy(() -> remote.call(() -> get(remote, "/err"))).isInstanceOf(HttpServerErrorException.class);
        }

        assertThatThrownBy(() -> remote.callIdempotent(() -> get(remote, "/err"))).isInstanceOf(RemoteService.CircuitOpenException.class);
    }
}
