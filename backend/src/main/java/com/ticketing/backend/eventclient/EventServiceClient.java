package com.ticketing.backend.eventclient;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.dto.EventExistsResponse;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.ticketing.backend.remote.RemoteService;
import com.ticketing.backend.remote.RemoteServices;
import org.springframework.web.client.RestClientException;

/** 외부 클라이언트용인 공개 게이트웨이를 거치지 않고 docker 네트워크로 event-service와 직접 통신한다. */
@Component
public class EventServiceClient {

    private final RemoteService remote;

    public EventServiceClient(RemoteServices remotes, @Value("${event.service.uri}") String baseUrl) {
        this.remote = remotes.create("event-service", baseUrl);
    }

    public EventExistsResponse getEvent(Long eventId) {
        return call(
                () -> remote.rest().get().uri("/internal/events/{id}", eventId).retrieve().body(EventExistsResponse.class));
    }

    private <T> T call(Supplier<T> request) {
        try {
            return remote.callIdempotent(request); // 읽기
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
    }
}
