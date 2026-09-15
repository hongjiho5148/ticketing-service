package com.ticketing.backend.reservationclient;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.reservationclient.dto.ReservationDetailResponse;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Talks to reservation-service directly over the docker network - never through the public Gateway, which is for external clients only. */
@Component
public class ReservationServiceClient {

    private final RestClient restClient;

    public ReservationServiceClient(@Value("${reservation.service.uri}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ReservationDetailResponse getReservation(Long reservationId) {
        return call(() -> restClient
                .get()
                .uri("/internal/reservations/{id}", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    // reservation-service owns the "reservation lifecycle -> seat state" transition, mirroring the
    // sell() call its own ReservationExpirySweeper already makes on expiry - order-service no longer
    // calls event-service directly for this.
    public ReservationDetailResponse confirm(Long reservationId) {
        return call(() -> restClient
                .post()
                .uri("/internal/reservations/{id}/confirm", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    public ReservationDetailResponse cancel(Long reservationId) {
        return call(() -> restClient
                .post()
                .uri("/internal/reservations/{id}/cancel", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_FOUND);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.RESERVATION_SERVICE_UNAVAILABLE);
        }
    }
}
