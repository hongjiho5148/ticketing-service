package com.ticketing.eventservice.authclient;

import com.ticketing.eventservice.authclient.dto.UserInternalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ticketing.eventservice.remote.RemoteService;
import com.ticketing.eventservice.remote.RemoteServices;

/**
 * Talks to auth-service directly over the docker network - never through the public Gateway.
 * Only used for best-effort notification lookups (recipient email + opt-in), so callers handle a
 * failure themselves instead of this client turning it into an ApiException.
 */
@Component
public class AuthServiceClient {

    private final RemoteService remote;

    public AuthServiceClient(RemoteServices remotes, @Value("${auth.service.uri}") String baseUrl) {
        this.remote = remotes.create("auth-service", baseUrl);
    }

    public UserInternalResponse getUser(Long userId) {
        return remote.callIdempotent(
                () -> remote.rest().get().uri("/internal/users/{id}", userId).retrieve().body(UserInternalResponse.class));
    }
}
