package com.ticketing.authservice.auth;

import com.ticketing.authservice.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final long adminAccessTokenExpirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
            @Value("${jwt.admin-access-token-expiration-ms}") long adminAccessTokenExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.adminAccessTokenExpirationMs = adminAccessTokenExpirationMs;
    }

    public String createAccessToken(Long userId, String email, Role role) {
        return createToken(userId, email, role, accessTokenExpirationMs);
    }

    // Admin sessions are deliberately shorter-lived than user ones - a stolen admin token is the
    // one that matters, and the admin app has no refresh flow, so they just log in again.
    public String createAdminAccessToken(Long userId, String email, Role role) {
        return createToken(userId, email, role, adminAccessTokenExpirationMs);
    }

    public long getAdminAccessTokenExpirationSeconds() {
        return adminAccessTokenExpirationMs / 1000;
    }

    public String createRefreshToken(Long userId, String email, Role role) {
        return createToken(userId, email, role, refreshTokenExpirationMs);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    private String createToken(Long userId, String email, Role role, long expirationMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
