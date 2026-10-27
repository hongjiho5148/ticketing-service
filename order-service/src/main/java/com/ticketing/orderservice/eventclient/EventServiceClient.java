package com.ticketing.orderservice.eventclient;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.dto.SeatBatchRequest;
import com.ticketing.orderservice.eventclient.dto.SeatBatchResponse;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.ticketing.orderservice.remote.RemoteService;
import com.ticketing.orderservice.remote.RemoteServices;
import org.springframework.web.client.RestClientException;

/** Talks to event-service directly over the docker network - never through the public Gateway, which is for external clients only. */
@Component
public class EventServiceClient {

    private final RemoteService remote;

    public EventServiceClient(RemoteServices remotes, @Value("${event.service.uri}") String baseUrl) {
        this.remote = remotes.create("event-service", baseUrl);
    }

    public SeatDetailResponse getSeat(Long seatId) {
        return call(
                () -> remote.rest().get().uri("/internal/seats/{id}", seatId).retrieve().body(SeatDetailResponse.class));
    }

    public List<SeatDetailResponse> getSeats(List<Long> seatIds) {
        SeatBatchResponse response = call(() -> remote.rest()
                .post()
                .uri("/internal/seats/batch")
                .body(new SeatBatchRequest(seatIds))
                .retrieve()
                .body(SeatBatchResponse.class));
        return response.seats();
    }

    private <T> T call(Supplier<T> request) {
        try {
            return remote.callIdempotent(request); // both calls only read
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(ErrorCode.SEAT_NOT_FOUND);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
    }
}
