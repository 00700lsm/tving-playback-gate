package com.playbackgate.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {

    public static final String CLAIM_CONTENT_ID = "contentId";
    public static final String CLAIM_SESSION_ID = "sessionId";
    public static final String CLAIM_DEVICE_ID = "deviceId";
    public static final String CLAIM_TOKEN_TYPE = "tokenType";
    public static final String TOKEN_TYPE_AUTH = "AUTH";
    public static final String TOKEN_TYPE_PLAYBACK = "PLAYBACK";

    private final SecretKey secretKey;
    private final long authTokenValiditySeconds;

    public JwtProvider(
            @Value("${playback-gate.jwt.secret}") String secret,
            @Value("${playback-gate.jwt.auth-token-validity-seconds}") long authTokenValiditySeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.authTokenValiditySeconds = authTokenValiditySeconds;
    }

    public String createAuthToken(Long memberId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(authTokenValiditySeconds);
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_AUTH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public String createPlaybackToken(
            Long memberId,
            Long contentId,
            String sessionId,
            String deviceId,
            Instant expiresAt
    ) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_PLAYBACK)
                .claim(CLAIM_CONTENT_ID, contentId)
                .claim(CLAIM_SESSION_ID, sessionId)
                .claim(CLAIM_DEVICE_ID, deviceId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public Long parseAuthMemberId(String token) {
        Claims claims = parseClaims(token);
        if (!TOKEN_TYPE_AUTH.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new JwtException("Auth token이 아닙니다.");
        }
        return Long.valueOf(claims.getSubject());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
