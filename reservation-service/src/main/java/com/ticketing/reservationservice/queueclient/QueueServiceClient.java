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
 * Talks to backend's queue domain directly over the docker network - queue wasn't split out in
 * this phase, so validating a pass token means calling back into backend's internal API.
 * Fails closed: if backend can't be reached, we refuse the reservation rather than let an
 * unverified pass token through.
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
