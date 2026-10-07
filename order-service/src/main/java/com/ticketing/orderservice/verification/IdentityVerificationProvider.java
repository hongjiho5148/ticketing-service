package com.ticketing.orderservice.verification;

import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;

/**
 * One way of confirming that the person paying is who they say they are. The order flow only ever
 * talks to this interface, so replacing the self-attestation stand-in with a real provider (PASS,
 * PortOne identity verification, ...) means adding one bean and changing identity-verification.provider.
 */
public interface IdentityVerificationProvider {

    /** The value stored on the VerificationRecord and matched against identity-verification.provider. */
    String method();

    /** Returns normally when the user is verified; throws ApiException when they are not. */
    void verify(Long userId, VerifyIdentityRequest request);
}
