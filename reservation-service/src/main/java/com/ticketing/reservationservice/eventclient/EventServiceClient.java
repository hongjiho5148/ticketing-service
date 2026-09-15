package com.ticketing.reservationservice.eventclient;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.dto.HoldSeatRequest;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import java.time.LocalDateTime;
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
