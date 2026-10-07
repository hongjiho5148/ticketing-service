package com.ticketing.eventservice.authclient;

import com.ticketing.eventservice.authclient.dto.UserInternalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to auth-service directly over the docker network - never through the public Gateway.
 * Only used for best-effort notification lookups (recipient email + opt-in), so callers handle a
 * failure themselves instead of this client turning it into an ApiException.
 */
@Component
public class AuthServiceClient {

    private final RestClient restClient;

    public AuthServiceClient(@Value("${auth.service.uri}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public UserInternalResponse getUser(Long userId) {
        return restClient.get().uri("/internal/users/{id}", userId).retrieve().body(UserInternalResponse.class);
    }
}
