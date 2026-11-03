package com.ticketing.orderservice.verification;

import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;

/**
 * 결제하는 사람이 본인임을 확인하는 한 가지 방법이다. 주문 흐름은 이 인터페이스하고만 이야기하므로
 * 자기 확인(self-attestation) 대용품을 실제 제공자(PASS, PortOne 본인인증 등)로 바꾸려면 빈 하나를
 * 추가하고 identity-verification.provider를 바꾸면 된다.
 */
public interface IdentityVerificationProvider {

    /** VerificationRecord에 저장되고 identity-verification.provider와 대조되는 값이다. */
    String method();

    /** 사용자가 확인됐으면 정상적으로 돌아오고, 확인되지 않았으면 ApiException을 던진다. */
    void verify(Long userId, VerifyIdentityRequest request);
}
