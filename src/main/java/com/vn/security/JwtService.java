package com.vn.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
// Đây là một interface trong Java Cryptography Architecture (JCA) —
// bộ thư viện mã hóa built-in của Java, không cần thêm dependency nào.
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String ACCESS_TOKEN_TYPE = "ACCESS";
    private static final String REFRESH_TOKEN_TYPE = "REFRESH";

    private final SecretKey accessKey;
    private final long accessExpiry;
    private final long refreshExpiry;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry}") long accessExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshExpiry
    ) {
        this.accessKey    = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiry  = accessExpiry;
        this.refreshExpiry = refreshExpiry;
    }

    public String generateAccessToken(String email, Long userId) {
        return buildToken(email, userId, accessExpiry, ACCESS_TOKEN_TYPE);
    }

    public String generateRefreshToken(String email, Long userId) {
        return buildToken(email, userId, refreshExpiry, REFRESH_TOKEN_TYPE);
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        return getClaims(token).get("userId", Long.class);
    }

    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            return false;
        } catch (JwtException e) {
            return false;
        }
    }

    public boolean isAccessToken(String token) {
        return isTokenOfType(token, ACCESS_TOKEN_TYPE);
    }

    public boolean isRefreshToken(String token) {
        return isTokenOfType(token, REFRESH_TOKEN_TYPE);
    }

    public long getAccessExpiry() {
        return accessExpiry;
    }

    public long getRefreshExpiry() {
        return refreshExpiry;
    }

    // ── private ──────────────────────────────────────────
    // buildToken: Tạo JWT token với thông tin người dùng
    private String buildToken(String email, Long userId, long expiry, String tokenType) {
        return Jwts.builder()
                .subject(email)                          // 0.12.x: subject() thay vì setSubject()
                .claim("userId", userId)
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .issuedAt(new Date())                    // 0.12.x: issuedAt() thay vì setIssuedAt()
                .expiration(new Date(System.currentTimeMillis() + expiry))
                .signWith(accessKey)                     // 0.12.x: chỉ cần key, tự suy ra algorithm
                .compact();
    }

    private boolean isTokenOfType(String token, String expectedTokenType) {
        try {
            return expectedTokenType.equals(getClaims(token).get(TOKEN_TYPE_CLAIM, String.class));
        } catch (JwtException e) {
            return false;
        }
    }

    // getClaims: Giải mã JWT token và trả về claims
    private Claims getClaims(String token) {
        // Builder method pattern: parser() → verifyWith() → build() → parseSignedClaims()
        return Jwts.parser()                             // 0.12.x: parser() thay vì parserBuilder()
                .verifyWith(accessKey)                   // 0.12.x: verifyWith() thay vì setSigningKey()
                .build()
                .parseSignedClaims(token)                // 0.12.x: parseSignedClaims() thay vì parseClaimsJws()
                .getPayload();
    }
}
