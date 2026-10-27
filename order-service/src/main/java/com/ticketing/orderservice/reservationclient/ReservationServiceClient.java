package com.ticketing.orderservice.reservationclient;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.reservationclient.dto.ReservationDetailResponse;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.ticketing.orderservice.remote.RemoteService;
import com.ticketing.orderservice.remote.RemoteServices;
import org.springframework.web.client.RestClientException;

/** Talks to reservation-service directly over the docker network - never through the public Gateway, which is for external clients only. */
@Component
public class ReservationServiceClient {

    private final RemoteService remote;

    public ReservationServiceClient(RemoteServices remotes, @Value("${reservation.service.uri}") String baseUrl) {
        // confirm() makes reservation-service call event-service, which can itself take up to its own read timeout, so
        // this one waits longer than that - otherwise it would give up on a confirmation that is about to succeed
        this.remote = remotes.create("reservation-service", baseUrl, Duration.ofSeconds(8));
    }

    public ReservationDetailResponse getReservation(Long reservationId) {
        return call(() -> remote.rest()
                .get()
                .uri("/internal/reservations/{id}", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    // reservation-service owns the "reservation lifecycle -> seat state" transition, mirroring the
    // sell() call its own ReservationExpirySweeper already makes on expiry - order-service never
    // calls event-service directly to mutate seat state.
    public ReservationDetailResponse confirm(Long reservationId) {
        return call(() -> remote.rest()
                .post()
                .uri("/internal/reservations/{id}/confirm", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    public ReservationDetailResponse cancel(Long reservationId) {
        return call(() -> remote.rest()
                .post()
                .uri("/internal/reservations/{id}/cancel", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    private <T> T call(Supplier<T> request) {
        try {
            return remote.callIdempotent(request); // a read, and confirm/cancel return early when already done
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_FOUND);
        } catch (HttpClientErrorException.Gone e) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.RESERVATION_SERVICE_UNAVAILABLE);
        }
    }
}
