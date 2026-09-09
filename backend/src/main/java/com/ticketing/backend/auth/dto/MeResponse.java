package com.ticketing.backend.auth.dto;

import com.ticketing.backend.user.User;

public record MeResponse(Long id, String email, String name) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getName());
    }
}
