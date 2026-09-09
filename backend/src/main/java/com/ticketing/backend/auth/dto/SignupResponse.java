package com.ticketing.backend.auth.dto;

import com.ticketing.backend.user.User;

public record SignupResponse(Long id, String email, String name) {

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getName());
    }
}
