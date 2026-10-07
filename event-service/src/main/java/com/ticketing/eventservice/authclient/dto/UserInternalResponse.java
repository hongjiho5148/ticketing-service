package com.ticketing.eventservice.authclient.dto;

/** Mirrors auth-service's internal UserInternalResponse. */
public record UserInternalResponse(Long userId, String email, String name, boolean emailOptIn) {
}
