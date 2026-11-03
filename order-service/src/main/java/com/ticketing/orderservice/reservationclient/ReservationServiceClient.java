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

/** 외부 클라이언트용인 공개 게이트웨이를 거치지 않고 docker 네트워크로 reservation-service와 직접 통신한다. */
@Component
public class ReservationServiceClient {

    private final RemoteService remote;

    public ReservationServiceClient(RemoteServices remotes, @Value("${reservation.service.uri}") String baseUrl) {
        // confirm()은 reservation-service가 event-service를 호출하게 하고, 그 호출은 그쪽 읽기 타임아웃까지 걸릴 수
        // 있다. 그래서 이 호출은 그보다 오래 기다린다. 그렇지 않으면 곧 성공할 확정을 포기해 버린다
        this.remote = remotes.create("reservation-service", baseUrl, Duration.ofSeconds(8));
    }

    public ReservationDetailResponse getReservation(Long reservationId) {
        return call(() -> remote.rest()
                .get()
                .uri("/internal/reservations/{id}", reservationId)
                .retrieve()
                .body(ReservationDetailResponse.class));
    }

    // "예약 생명주기 -> 좌석 상태" 전환은 reservation-service가 맡는다. 만료 때 ReservationExpirySweeper가
    // 이미 하는 sell() 호출과 같은 방식이다. order-service는 좌석 상태를 바꾸려고 event-service를 직접 호출하지 않는다.
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
            return remote.callIdempotent(request); // 읽기이고, confirm/cancel은 이미 끝났으면 일찍 돌아온다
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
