package com.ticketing.orderservice.verification;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real identity check: the buyer ticks a box saying they are paying under their own
 * name and not buying to resell. It proves nothing by itself - what it adds is a recorded,
 * per-order statement we can point to if a purchase is later disputed - and it is deliberately the
 * only place that would change when a real provider is connected.
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
