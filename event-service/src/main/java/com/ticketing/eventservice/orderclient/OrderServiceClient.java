package com.ticketing.eventservice.orderclient;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.orderclient.dto.OrderExistsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Talks to order-service directly over the docker network - never through the public Gateway. */
@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(@Value("${order.service.uri}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean hasPaidOrder(Long userId, Long eventId) {
        try {
            OrderExistsResponse response = restClient
                    .get()
                    .uri("/internal/orders/exists?userId={userId}&eventId={eventId}", userId, eventId)
                    .retrieve()
                    .body(OrderExistsResponse.class);
            return response != null && response.exists();
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.ORDER_SERVICE_UNAVAILABLE);
        }
    }
}
