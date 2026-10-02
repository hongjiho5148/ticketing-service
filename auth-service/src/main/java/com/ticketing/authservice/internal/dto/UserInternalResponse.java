package com.ticketing.authservice.internal.dto;

public record UserInternalResponse(Long userId, String email, String name, boolean emailOptIn) {
}
