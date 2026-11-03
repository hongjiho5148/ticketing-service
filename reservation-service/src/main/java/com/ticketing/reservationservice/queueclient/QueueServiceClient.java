package com.ticketing.reservationservice.queueclient;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.queueclient.dto.PassTokenValidResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ticketing.reservationservice.remote.RemoteService;
import com.ticketing.reservationservice.remote.RemoteServices;
import org.springframework.web.client.RestClientException;

/**
 * docker 네트워크로 backend의 대기열 도메인과 직접 통신한다. 이 단계에서는 대기열을 분리하지 않았으므로
 * 입장권 토큰을 검증하려면 backend의 내부 API를 다시 호출해야 한다.
 * 실패하면 닫는다(fail closed). backend에 닿지 못하면 검증되지 않은 입장권 토큰을 통과시키지 않고
 * 예약을 거절한다.
 */
@Component
public class QueueServiceClient {

    private final RemoteService remote;

    public QueueServiceClient(RemoteServices remotes, @Value("${queue.service.uri}") String baseUrl) {
        this.remote = remotes.create("queue-service", baseUrl);
    }

    public boolean isPassTokenValid(Long eventId, String passToken) {
        try {
            PassTokenValidResponse response = remote.callIdempotent(() -> remote.rest()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/queue/pass-token/valid")
                            .queryParam("eventId", eventId)
                            .queryParam("passToken", passToken == null ? "" : passToken)
                            .build())
                    .retrieve()
                    .body(PassTokenValidResponse.class));
            return response != null && response.valid();
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.QUEUE_SERVICE_UNAVAILABLE);
        }
    }
}
