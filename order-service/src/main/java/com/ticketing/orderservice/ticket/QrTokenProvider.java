package com.ticketing.orderservice.ticket;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs/verifies the entry QR itself - deliberately a separate secret from the user-auth
 * jwt.secret so a QR shown at the gate (easy to photograph or shoulder-surf) can never be reused
 * to forge a login token, and vice versa.
 */
@Component
public class QrTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public QrTokenProvider(
            @Value("${ticket.qr-token.secret}") String secret,
            @Value("${ticket.qr-token.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationMs = expirationMs;
    }

    // issuedAt is the ticket's own persisted issuedAt, not "now" - so repeated calls (the user
    // reopening the ticket page) always regenerate the same token until it actually expires,
    // instead of silently extending its lifetime on every view.
    public String issueToken(Long ticketId, String jti, LocalDateTime issuedAt) {
        Date iat = toDate(issuedAt);
        Date exp = new Date(iat.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(ticketId))
                .id(jti)
                .issuedAt(iat)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /** Throws JwtException (expired/malformed/bad signature) or IllegalArgumentException - caller decides the error. */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Date toDate(LocalDateTime dateTime) {
        return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
