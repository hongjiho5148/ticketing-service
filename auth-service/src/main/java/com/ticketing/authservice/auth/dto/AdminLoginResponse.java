package com.ticketing.authservice.auth.dto;

// 일부러 리프레시 토큰이 없다. 관리자 세션은 짧고 만료되면 그냥 다시 로그인한다.
public record AdminLoginResponse(String accessToken, long expiresIn) {
}
