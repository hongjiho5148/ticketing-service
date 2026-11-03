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
        // 외부 API이므로 우리 서비스끼리의 호출보다 조금 더 길게 허용한다
        this.remote = remotes.create("portone", baseUrl, Duration.ofSeconds(10));
        this.apiSecret = apiSecret;
    }

    /** PortOne 서버에서 결제를 조회한다. 실제로 성공했는지에 대한 유일한 진실의 원천이다. */
    public PortOnePaymentResponse getPayment(String paymentId) {
        return remote.callIdempotent(() -> remote.rest()
                .get()
                .uri("/payments/{paymentId}", paymentId)
                .header("Authorization", "PortOne " + apiSecret)
                .retrieve()
                .body(PortOnePaymentResponse.class));
    }

    /** PortOne으로 전액 환불한다. PortOne이 거절하면(예: 이미 취소됨) 예외를 던진다. */
    public void cancelPayment(String paymentId, String reason) {
        cancelPayment(paymentId, reason, null);
    }

    /** {@code amount}원만 환불한다(null이면 남은 금액 전부). PortOne이 거절하면 예외를 던진다. */
    public void cancelPayment(String paymentId, String reason, Long amount) {
        Map<String, Object> body = amount == null ? Map.of("reason", reason) : Map.of("reason", reason, "amount", amount);
        // 환불은 스스로 반복하지 않는다. 응답이 유실되면 조정 스윕이 그 결제를 다시 확인한다
        remote.call(() -> remote.rest()
                .post()
                .uri("/payments/{paymentId}/cancel", paymentId)
                .header("Authorization", "PortOne " + apiSecret)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }
}
