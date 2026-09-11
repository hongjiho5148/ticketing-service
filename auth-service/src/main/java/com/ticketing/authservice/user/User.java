package com.ticketing.authservice.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_users_provider_email", columnNames = {"provider", "email"}),
            @UniqueConstraint(name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(length = 100)
    private String providerId;

    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean emailVerified;

    @Column(length = 100)
    private String emailVerificationToken;

    private LocalDateTime emailVerificationExpiresAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private User(
            AuthProvider provider, String providerId, String email, String password, String name, boolean emailVerified) {
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = Role.USER;
        this.emailVerified = emailVerified;
    }

    public static User localSignup(String email, String encodedPassword, String name) {
        return new User(AuthProvider.LOCAL, null, email, encodedPassword, name, false);
    }

    public static User oauthSignup(AuthProvider provider, String providerId, String email, String name) {
        return new User(provider, providerId, email, null, name, true);
    }

    public void issueEmailVerificationToken(String token, LocalDateTime expiresAt) {
        this.emailVerificationToken = token;
        this.emailVerificationExpiresAt = expiresAt;
    }

    public void verifyEmail() {
        this.emailVerified = true;
        this.emailVerificationToken = null;
        this.emailVerificationExpiresAt = null;
    }
}
