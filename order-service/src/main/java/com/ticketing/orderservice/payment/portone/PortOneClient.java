package com.ticketing.orderservice.payment.portone;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PortOneClient {

    private final RestClient restClient;
    private final String apiSecret;

    public PortOneClient(
            @Value("${portone.api-base-url}") String baseUrl, @Value("${portone.api-secret}") String apiSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiSecret = apiSecret;
    }

    /** Looks a payment up on PortOne's own server - the only source of truth for whether it actually succeeded. */
    public PortOnePaymentResponse getPayment(String paymentId) {
        return restClient
                .get()
                .uri("/payments/{paymentId}", paymentId)
                .header("Authorization", "PortOne " + apiSecret)
                .retrieve()
                .body(PortOnePaymentResponse.class);
    }

    /** Issues a full refund through PortOne. Throws if PortOne rejects it (e.g. already cancelled). */
    public void cancelPayment(String paymentId, String reason) {
        cancelPayment(paymentId, reason, null);
    }

    /** Refunds only {@code amount} won (null = everything left). Throws if PortOne rejects it. */
    public void cancelPayment(String paymentId, String reason, Long amount) {
        Map<String, Object> body = amount == null ? Map.of("reason", reason) : Map.of("reason", reason, "amount", amount);
        restClient
                .post()
                .uri("/payments/{paymentId}/cancel", paymentId)
                .header("Authorization", "PortOne " + apiSecret)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
