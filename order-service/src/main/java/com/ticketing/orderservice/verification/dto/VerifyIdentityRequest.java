package com.ticketing.orderservice.verification.dto;

/**
 * What the buyer sends to confirm themselves. Only {@code agreed} matters for self-attestation; a
 * real provider would carry its own proof here (for example a PortOne identityVerificationId).
 */
public record VerifyIdentityRequest(Boolean agreed) {
}
