package com.ticketing.reservationservice.eventclient;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.dto.HoldSeatRequest;
import com.ticketing.reservationservice.eventclient.dto.ReleaseSeatRequest;
import com.ticketing.reservationservice.eventclient.dto.SellSeatRequest;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.ticketing.reservationservice.remote.RemoteService;
import com.ticketing.reservationservice.remote.RemoteServices;
import org.springframework.web.client.RestClientException;

/** 외부 클라이언트용인 공개 게이트웨이를 거치지 않고 docker 네트워크로 event-service와 직접 통신한다. */
@Component
public class EventServiceClient {

    private final RemoteService remote;

    public EventServiceClient(RemoteServices remotes, @Value("${event.service.uri}") String baseUrl) {
        this.remote = remotes.create("event-service", baseUrl);
    }

    public SeatDetailResponse getSeat(Long seatId) {
        return call(
                () -> remote.rest().get().uri("/internal/seats/{id}", seatId).retrieve().body(SeatDetailResponse.class),
                ErrorCode.SEAT_NOT_FOUND, true);
    }

    // 예전에 ReservationService가 SeatLockService 아래에서 프로세스 안에서 돌리던 것과 같은 핵심 작업이다.
    // 이제는 그 잠금을 직접 쥐고 있는 event-service에 대한 원자적 호출 한 번이다.
    public SeatDetailResponse hold(Long seatId, LocalDateTime holdExpireAt) {
        return call(
                () -> remote.rest()
                        .post()
                        .uri("/internal/seats/{id}/hold", seatId)
                        .body(new HoldSeatRequest(holdExpireAt))
                        .retrieve()
                        .body(SeatDetailResponse.class),
                ErrorCode.SEAT_NOT_FOUND, false);
    }

    /** 좌석이 아직 {@code holdExpireAt}에 끝나는 그 홀드에 잡혀 있을 때만 좌석을 푼다. */
    public void release(Long seatId, LocalDateTime holdExpireAt) {
        call(() -> remote.rest()
                        .post()
                        .uri("/internal/seats/{id}/release", seatId)
                        .body(new ReleaseSeatRequest(holdExpireAt))
                        .retrieve()
                        .toBodilessEntity(),
                ErrorCode.SEAT_NOT_FOUND, true);
    }

    /** 좌석이 아직 {@code holdExpireAt}에 끝나는 그 홀드에 잡혀 있을 때만 좌석을 판매 처리한다. 아니면 HOLD_EXPIRED다. */
    public void sell(Long seatId, LocalDateTime holdExpireAt) {
        call(() -> remote.rest()
                        .post()
                        .uri("/internal/seats/{id}/sell", seatId)
                        .body(new SellSeatRequest(holdExpireAt))
                        .retrieve()
                        .toBodilessEntity(),
                ErrorCode.SEAT_NOT_FOUND, true);
    }

    private <T> T call(Supplier<T> request, ErrorCode notFoundCode, boolean idempotent) {
        try {
            return idempotent ? remote.callIdempotent(request) : remote.call(request);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(notFoundCode);
        } catch (HttpClientErrorException.Gone e) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED);
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
    }
}
