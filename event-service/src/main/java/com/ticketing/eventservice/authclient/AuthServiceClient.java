package com.ticketing.eventservice.authclient;

import com.ticketing.eventservice.authclient.dto.UserInternalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ticketing.eventservice.remote.RemoteService;
import com.ticketing.eventservice.remote.RemoteServices;

/**
 * 공개 게이트웨이를 거치지 않고 docker 네트워크로 auth-service와 직접 통신한다.
 * 알림용 조회(수신자 이메일과 수신 동의)에만 쓰는 최선 노력 호출이라, 실패는 이 클라이언트가
 * ApiException으로 바꾸지 않고 호출하는 쪽이 직접 처리한다.
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
