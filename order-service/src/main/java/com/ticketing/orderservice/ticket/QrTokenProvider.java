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
 * 입장 QR 자체를 서명하고 검증한다. 사용자 인증용 jwt.secret과는 일부러 다른 비밀 값을 쓴다. 입구에서
 * 보여 주는 QR은 사진을 찍히거나 어깨너머로 보이기 쉬우므로, 그것으로 로그인 토큰을 위조하거나
 * 그 반대로 하는 일이 없게 한다.
 */
@Component
public class QrTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public QrTokenProvider(
            @Value("${ticket.qr-token.secret}") String secret,
            @Value("${ticket.qr-token.expiration-ms}") long expirationMs) {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "QR_TOKEN_SECRET must be set to a random value of at least 32 bytes. Generate one with: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationMs = expirationMs;
    }

    // issuedAt은 "지금"이 아니라 티켓에 저장된 발급 시각이다. 그래서 반복 호출(사용자가 티켓 화면을 다시
    // 여는 경우)해도 토큰이 실제로 만료될 때까지 항상 같은 토큰이 만들어지고, 볼 때마다 수명이 슬그머니 늘어나지 않는다.
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

    /** JwtException(만료/형식 오류/서명 오류)이나 IllegalArgumentException을 던진다. 어떤 오류로 응답할지는 호출한 쪽이 정한다. */
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
