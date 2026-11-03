package com.ticketing.authservice.auth;

/** 인증 토큰을 발급한 트랜잭션 안에서 발행한다. 메일 자체는 그것이 커밋된 뒤에 나간다. */
public record VerificationMailRequested(String to, String name, String token) {
}
