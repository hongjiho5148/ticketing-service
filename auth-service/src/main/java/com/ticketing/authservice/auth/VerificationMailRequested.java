package com.ticketing.authservice.auth;

/** Published inside the transaction that issued a verification token; the mail itself goes out after that commits. */
public record VerificationMailRequested(String to, String name, String token) {
}
