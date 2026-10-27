package com.ticketing.orderservice.authclient;

import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.ticketing.orderservice.remote.RemoteService;
import com.ticketing.orderservice.remote.RemoteServices;

/**
 * Talks to auth-service directly over the docker network - never through the public Gateway.
 * Used only for best-effort notification lookups (recipient email + opt-in), so callers decide
 * how to handle a failure here themselves rather than this client throwing an ApiException the
 * way EventServiceClient/ReservationServiceClient do for user-facing calls.
 */
@Component
public class AuthServiceClient {

    private final RemoteService remote;

    public AuthServiceClient(RemoteServices remotes, @Value("${auth.service.uri}") String baseUrl) {
        this.remote = remotes.create("auth-service", baseUrl);
    }

    /** The user registered under this email, or null when there is none. Other failures still throw. */
    public UserInternalResponse lookupByEmail(String email) {
        try {
            return remote.callIdempotent(() -> remote.rest().get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/users/lookup").queryParam("email", email).build())
                    .retrieve()
                    .body(UserInternalResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public UserInternalResponse getUser(Long userId) {
        return remote.callIdempotent(
                () -> remote.rest().get().uri("/internal/users/{id}", userId).retrieve().body(UserInternalResponse.class));
    }
}
