package com.ticketing.orderservice.verification;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import org.springframework.stereotype.Component;

/**
 * 실제 본인 확인의 대용품이다. 구매자가 본인 명의로 결제하며 되팔 목적이 아니라는 칸에 표시한다.
 * 그것만으로는 아무것도 증명하지 못한다. 더해 주는 것은 나중에 구매가 분쟁이 됐을 때 내밀 수 있는, 주문별로
 * 기록된 진술이다. 실제 제공자를 연결할 때 바뀌는 곳은 일부러 이곳 하나뿐이다.
 */
@Component
public class SelfAttestedVerificationProvider implements IdentityVerificationProvider {

    public static final String METHOD = "SELF_ATTESTED";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public void verify(Long userId, VerifyIdentityRequest request) {
        if (!Boolean.TRUE.equals(request.agreed())) {
            throw new ApiException(ErrorCode.IDENTITY_NOT_CONFIRMED);
        }
    }
}
