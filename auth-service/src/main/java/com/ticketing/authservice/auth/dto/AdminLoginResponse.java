package com.ticketing.authservice.auth.dto;

// No refresh token on purpose: admin sessions are short and just re-login when they expire.
public record AdminLoginResponse(String accessToken, long expiresIn) {
}
