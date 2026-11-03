package com.ticketing.orderservice.authclient.dto;

/** auth-service 내부 UserInternalResponse를 그대로 본뜬 것이다. */
public record UserInternalResponse(Long userId, String email, String name, boolean emailOptIn) {
}
