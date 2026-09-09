package com.ticketing.backend.auth.dto;

public record LoginResponse(String accessToken, String refreshToken, long expiresIn) {
}
