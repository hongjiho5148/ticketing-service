package com.ticketing.authservice.auth.dto;

public record LoginResponse(String accessToken, String refreshToken, long expiresIn) {
}
