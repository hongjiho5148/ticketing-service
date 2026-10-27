package com.ticketing.orderservice.payment.portone;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ticketing.orderservice.remote.RemoteService;
import com.ticketing.orderservice.remote.RemoteServices;

@Component
public class PortOneClient {

    private final RemoteService remote;
    private final String apiSecret;

    public PortOneClient(
            RemoteServices remotes,
            @Value("${portone.api-base-url}") String baseUrl,
            @Value("${portone.api-secret}") String apiSecret) {
        // an outside API: allowed a little longer than a call between our own services
        this.remote = remotes.create("portone", baseUrl, Duration.ofSeconds(10));
        this.apiSecret = apiSecret;
    }

    /** Looks a payment up on PortOne's own server - the only source of truth for whether it actually succeeded. */
    public PortOnePaymentResponse getPayment(String paymentId) {
        return remote.callIdempotent(() -> remote.rest()
                .get()
                .uri("/payments/{paymentId}", paymentId)
                .header("Authorization", "PortOne " + apiSecret)
                .retrieve()
                .body(PortOnePaymentResponse.class));
    }

    /** Issues a full refund through PortOne. Throws if PortOne rejects it (e.g. already cancelled). */
    public void cancelPayment(String paymentId, String reason) {
        cancelPayment(paymentId, reason, null);
    }

    /** Refunds only {@code amount} won (null = everything left). Throws if PortOne rejects it. */
    public void cancelPayment(String paymentId, String reason, Long amount) {
        Map<String, Object> body = amount == null ? Map.of("reason", reason) : Map.of("reason", reason, "amount", amount);
        // a refund is not repeated on its own: if the answer is lost the reconciliation sweep looks at the payment again
        remote.call(() -> remote.rest()
                .post()
                .uri("/payments/{paymentId}/cancel", paymentId)
                .header("Authorization", "PortOne " + apiSecret)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }
}
