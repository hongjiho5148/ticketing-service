package com.ticketing.authservice.auth.dto;

import com.ticketing.authservice.user.User;

public record SignupResponse(Long id, String email, String name) {

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getName());
    }
}
