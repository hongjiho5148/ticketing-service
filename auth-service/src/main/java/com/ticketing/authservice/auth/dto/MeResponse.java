package com.ticketing.authservice.auth.dto;

import com.ticketing.authservice.user.AuthProvider;
import com.ticketing.authservice.user.Role;
import com.ticketing.authservice.user.User;

public record MeResponse(Long id, String email, String name, AuthProvider provider, Role role) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getProvider(), user.getRole());
    }
}
