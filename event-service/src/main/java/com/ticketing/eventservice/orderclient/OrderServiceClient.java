package com.ticketing.eventservice.orderclient;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.orderclient.dto.OrderExistsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ticketing.eventservice.remote.RemoteService;
import com.ticketing.eventservice.remote.RemoteServices;
import org.springframework.web.client.RestClientException;

/** 공개 게이트웨이를 거치지 않고 docker 네트워크로 order-service와 직접 통신한다. */
@Component
public class OrderServiceClient {

    private final RemoteService remote;

    public OrderServiceClient(RemoteServices remotes, @Value("${order.service.uri}") String baseUrl) {
        this.remote = remotes.create("order-service", baseUrl);
    }

    public boolean hasPaidOrder(Long userId, Long eventId) {
        try {
            OrderExistsResponse response = remote.callIdempotent(() -> remote.rest()
                    .get()
                    .uri("/internal/orders/exists?userId={userId}&eventId={eventId}", userId, eventId)
                    .retrieve()
                    .body(OrderExistsResponse.class));
            return response != null && response.exists();
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.ORDER_SERVICE_UNAVAILABLE);
        }
    }
}
