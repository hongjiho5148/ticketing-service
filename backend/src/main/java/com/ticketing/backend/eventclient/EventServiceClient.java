package com.ticketing.backend.eventclient;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.dto.EventExistsResponse;
import com.ticketing.backend.eventclient.dto.HoldSeatRequest;
import com.ticketing.backend.eventclient.dto.SeatBatchRequest;
import com.ticketing.backend.eventclient.dto.SeatBatchResponse;
import com.ticketing.backend.eventclient.dto.SeatDetailResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Talks to event-service directly over the docker network - never through the public Gateway, which is for external clients only. */
@Component
public class EventServiceClient {

    private final RestClient restClient;

    public EventServiceClient(@Value("${event.service.uri}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public SeatDetailResponse getSeat(Long seatId) {
        return call(
                () -> restClient.get().uri("/internal/seats/{id}", seatId).retrieve().body(SeatDetailResponse.class),
                ErrorCode.SEAT_NOT_FOUND);
    }

    public List<SeatDetailResponse> getSeats(List<Long> seatIds) {
        SeatBatchResponse response = call(
                () -> restClient
                        .post()
                        .uri("/internal/seats/batch")
                        .body(new SeatBatchRequest(seatIds))
                        .retrieve()
                        .body(SeatBatchResponse.class),
                ErrorCode.SEAT_NOT_FOUND);
        return response.seats();
    }

    // Same critical operation ReservationService used to run in-process under SeatLockService -
    // now a single atomic call to event-service, which holds that lock itself.
    public SeatDetailResponse hold(Long seatId, LocalDateTime holdExpireAt) {
        return call(
                () -> restClient
                        .post()
                        .uri("/internal/seats/{id}/hold", seatId)
                        .body(new HoldSeatRequest(holdExpireAt))
                        .retrieve()
                        .body(SeatDetailResponse.class),
                ErrorCode.SEAT_NOT_FOUND);
    }

    public void release(Long seatId) {
        call(() -> restClient.post().uri("/internal/seats/{id}/release", seatId).retrieve().toBodilessEntity(),
                ErrorCode.SEAT_NOT_FOUND);
    }

    public void sell(Long seatId) {
        call(() -> restClient.post().uri("/internal/seats/{id}/sell", seatId).retrieve().toBodilessEntity(),
                ErrorCode.SEAT_NOT_FOUND);
    }

    public EventExistsResponse getEvent(Long eventId) {
        return call(
                () -> restClient.get().uri("/internal/events/{id}", eventId).retrieve().body(EventExistsResponse.class),
                ErrorCode.EVENT_NOT_FOUND);
    }

    private <T> T call(Supplier<T> request, ErrorCode notFoundCode) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(notFoundCode);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
    }
}
