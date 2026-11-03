package com.ticketing.orderservice.verification.dto;

/**
 * 구매자가 본인을 확인하려고 보내는 것이다. 자기 확인에서는 {@code agreed}만 의미가 있고, 실제 제공자는
 * 여기에 자기 증빙(예: PortOne identityVerificationId)을 담는다.
 */
public record VerifyIdentityRequest(Boolean agreed) {
}
