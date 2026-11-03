package com.ticketing.orderservice.authclient;

import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import com.ticketing.orderservice.remote.RemoteService;
import com.ticketing.orderservice.remote.RemoteServices;

/**
 * 공개 게이트웨이를 거치지 않고 docker 네트워크로 auth-service와 직접 통신한다.
 * 알림용 조회(수신자 이메일과 수신 동의)에만 쓰는 최선 노력 호출이라, 실패했을 때 어떻게 할지는
 * 호출하는 쪽이 정한다. EventServiceClient/ReservationServiceClient가 사용자에게 보이는 호출에서
 * 하듯이 이 클라이언트가 ApiException을 던지지는 않는다.
 */
@Component
public class AuthServiceClient {

    private final RemoteService remote;

    public AuthServiceClient(RemoteServices remotes, @Value("${auth.service.uri}") String baseUrl) {
        this.remote = remotes.create("auth-service", baseUrl);
    }

    /** 이 이메일로 가입한 사용자. 없으면 null이다. 다른 실패는 그대로 예외를 던진다. */
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
