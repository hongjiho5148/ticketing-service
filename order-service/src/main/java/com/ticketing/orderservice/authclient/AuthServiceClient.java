package com.ticketing.orderservice.authclient;

import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Talks to auth-service directly over the docker network - never through the public Gateway.
 * Used only for best-effort notification lookups (recipient email + opt-in), so callers decide
 * how to handle a failure here themselves rather than this client throwing an ApiException the
 * way EventServiceClient/ReservationServiceClient do for user-facing calls.
 */
@Component
public class AuthServiceClient {

    private final RestClient restClient;

    public AuthServiceClient(@Value("${auth.service.uri}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** The user registered under this email, or null when there is none. Other failures still throw. */
    public UserInternalResponse lookupByEmail(String email) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/users/lookup").queryParam("email", email).build())
                    .retrieve()
                    .body(UserInternalResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public UserInternalResponse getUser(Long userId) {
        return restClient.get().uri("/internal/users/{id}", userId).retrieve().body(UserInternalResponse.class);
    }
}
